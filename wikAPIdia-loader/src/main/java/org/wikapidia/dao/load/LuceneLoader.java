package org.wikapidia.dao.load;

import org.apache.commons.cli.*;
import org.apache.commons.io.IOUtils;
import org.wikapidia.conf.ConfigurationException;
import org.wikapidia.conf.Configurator;
import org.wikapidia.conf.DefaultOptionBuilder;
import org.wikapidia.core.WikapidiaException;
import org.wikapidia.core.cmd.Env;
import org.wikapidia.core.cmd.EnvBuilder;
import org.wikapidia.core.dao.DaoException;
import org.wikapidia.core.dao.DaoFilter;
import org.wikapidia.core.dao.MetaInfoDao;
import org.wikapidia.core.dao.RawPageDao;
import org.wikapidia.core.lang.Language;
import org.wikapidia.core.lang.LanguageSet;
import org.wikapidia.core.model.NameSpace;
import org.wikapidia.core.model.RawPage;
import org.wikapidia.lucene.LuceneIndexer;
import org.wikapidia.lucene.LuceneOptions;
import org.wikapidia.lucene.LuceneSearcher;
import org.wikapidia.utils.ParallelForEach;
import org.wikapidia.utils.Procedure;
import org.wikapidia.utils.WpThreadUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * This loader indexes raw pages into the lucene index.
 * It should not be called sooner than the WikiTextLoader,
 * but where after that I am not sure.
 *
 * @author Ari Weiland
 *
 */
public class LuceneLoader {
    private static final Logger LOG = Logger.getLogger(LuceneLoader.class.getName());

    private static final RawPage POISON_PILL =
            new RawPage(0, 0, "", null, null, Language.getByLangCode("en"), null);

    // maximum number of raw pages in the parsing buffer
    public static final int MAX_QUEUE = 1000;

    private final RawPageDao rawPageDao;
    private final Collection<NameSpace> namespaces;


    private final BlockingQueue<RawPage> queue = new ArrayBlockingQueue<RawPage>(MAX_QUEUE);
    private final List<Thread> workers = new ArrayList<Thread>();
    private final MetaInfoDao metaDao;
    private final LuceneOptions[] luceneOptions;

    private LuceneIndexer luceneIndexer;

    public LuceneLoader(RawPageDao rawPageDao, MetaInfoDao metaDao, LuceneOptions[] luceneOptions, Collection<NameSpace> namespaces) {
        this.rawPageDao = rawPageDao;
        this.metaDao = metaDao;
        this.luceneOptions = luceneOptions;
        this.namespaces = namespaces;
    }

    /**
     * NOTE: only one language can be loaded at a time.
     * @param language
     * @throws WikapidiaException
     */
    public synchronized void load(Language language) throws WikapidiaException, ConfigurationException {
        try {
            createWorkers();
            DaoFilter filter = new DaoFilter()
                    .setLanguages(language)
                    .setNameSpaces(namespaces)
                    .setRedirect(false);
            int n = rawPageDao.getCount(filter);
            int i = 0;
            luceneIndexer = new LuceneIndexer(language, luceneOptions);
            for (RawPage rawPage : rawPageDao.get(filter)) {
                queue.put(rawPage);
                if (++i % 1000 == 0) {
                    LOG.log(Level.INFO, "RawPages indexed " + language + ": " + i + " of " + n);
                }
            }
            queue.put(POISON_PILL);
        } catch (DaoException e) {
            throw new WikapidiaException(e);
        } catch (InterruptedException e) {
            throw new WikapidiaException(e);
        } finally {
            cleanupWorkers();
            queue.clear();
            if (luceneIndexer != null) {
                IOUtils.closeQuietly(luceneIndexer);
                luceneIndexer = null;
            }
        }
    }

    public void endLoad() {
        if (luceneIndexer != null) {
            luceneIndexer.close();
        }
    }

    private void createWorkers() {
        workers.clear();
        for (int i = 0; i < WpThreadUtils.getMaxThreads(); i++) {
            Thread t = new Thread(new Worker());
            t.start();
            workers.add(t);
        }
    }

    private void cleanupWorkers() {
        long maxMillis = System.currentTimeMillis() + 2 * 60 * 1000;
        for (Thread w : workers) {
            try {
                w.join(Math.max(0, maxMillis - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                LOG.log(Level.SEVERE, "ignoring interrupted exception on thread join", e);
            }
        }
        for (Thread w : workers) {
            w.interrupt();
        }
        workers.clear();
    }

    private class Worker implements Runnable {
        public Worker() { }
        @Override
        public void run() {
            boolean finished = false;
            while (!finished) {
                RawPage rp = null;
                Language lang = null;
                try {
                    rp = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (rp == POISON_PILL) {
                        queue.put(rp);
                        finished = true;
                    } else if (rp != null) {
                        lang = rp.getLanguage();
                        luceneIndexer.indexPage(rp);
                        metaDao.incrementRecords(LuceneSearcher.class, lang);
                    }
                } catch (InterruptedException e) {
                    LOG.log(Level.WARNING, "LuceneLoader.Worker received interrupt.");
                    return;
                } catch (Exception e) {
                    metaDao.incrementErrorsQuietly(LuceneSearcher.class, lang);
                    String title = "unknown";
                    if (rp != null) title = rp.getTitle().toString();
                    LOG.log(Level.WARNING, "exception while parsing " + title, e);
                }
            }
        }
    }

    public static void main(String args[]) throws ConfigurationException, WikapidiaException, IOException, DaoException {
        Options options = new Options();
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("drop-indexes")
                        .withDescription("drop and recreate all indexes")
                        .create("d"));
        options.addOption(
                new DefaultOptionBuilder()
                        .hasArgs()
                        .withValueSeparator(',')
                        .withLongOpt("namespaces")
                        .withDescription("the set of namespaces to index, separated by commas")
                        .create("p"));
        options.addOption(
                new DefaultOptionBuilder()
                        .hasArgs()
                        .withValueSeparator(',')
                        .withLongOpt("indexes")
                        .withDescription("the types of indexes to store, separated by commas")
                        .create("i"));
        EnvBuilder.addStandardOptions(options);

        CommandLineParser parser = new PosixParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println("Invalid option usage: " + e.getMessage());
            new HelpFormatter().printHelp("LuceneLoader", options);
            return;
        }

        Env env = new EnvBuilder(cmd).build();
        Configurator conf = env.getConfigurator();

        LuceneOptions[] luceneOptions;
        if (cmd.hasOption("i")) {
            String[] optionType = cmd.getOptionValues("i");
            luceneOptions = new LuceneOptions[optionType.length];
            for (int i=0; i<optionType.length; i++) {
                luceneOptions[i] = conf.get(LuceneOptions.class, optionType[i]);
            }
        } else {
            luceneOptions = new LuceneOptions[] {
                    conf.get(LuceneOptions.class, "plaintext"),
                    conf.get(LuceneOptions.class, "esa")
            };
        }

        LanguageSet languages = env.getLanguages();
        Collection<NameSpace> namespaces = new ArrayList<NameSpace>();
        if (cmd.hasOption("p")) {
            String[] nsStrings = cmd.getOptionValues("p");
            for (String s : nsStrings) {
                namespaces.add(NameSpace.getNameSpaceByName(s));
            }
        } else {
            namespaces = luceneOptions[0].namespaces;
        }
        RawPageDao rawPageDao = conf.get(RawPageDao.class);
        MetaInfoDao metaDao = conf.get(MetaInfoDao.class);
        metaDao.beginLoad();
        for (Language lang : languages) {
            metaDao.clear(LuceneSearcher.class, lang);
        }

        final LuceneLoader loader = new LuceneLoader(rawPageDao, metaDao, luceneOptions, namespaces);

        LOG.log(Level.INFO, "Begin indexing");

        for (Language lang : languages) {
            loader.load(lang);
        }

        loader.endLoad();
        metaDao.endLoad();
        LOG.log(Level.INFO, "Done indexing");
    }
}
