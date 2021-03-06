package org.wikibrain.dao.load;

import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;
import org.apache.commons.cli.*;
import org.apache.commons.lang.StringUtils;
import org.wikibrain.conf.ConfigurationException;
import org.wikibrain.conf.Configurator;
import org.wikibrain.conf.DefaultOptionBuilder;
import org.wikibrain.core.cmd.Env;
import org.wikibrain.core.cmd.EnvBuilder;
import org.wikibrain.core.cmd.FileMatcher;
import org.wikibrain.core.dao.*;
import org.wikibrain.core.lang.Language;
import org.wikibrain.core.lang.LanguageInfo;
import org.wikibrain.core.model.LocalLink;
import org.wikibrain.core.model.NameSpace;
import org.wikibrain.core.model.Title;
import org.wikibrain.parser.sql.MySqlDumpParser;
import org.wikibrain.utils.ParallelForEach;
import org.wikibrain.utils.Procedure;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Loads links that are in the SQL dump but not the parsed wiki text.
 */
public class SqlLinksLoader {

    private static final Logger LOG = Logger.getLogger(SqlLinksLoader.class.getName());

    private final AtomicInteger counter = new AtomicInteger();
    private final File sqlDump;
    private final Language language;

    private final LocalLinkDao dao;
    private final LocalPageDao pageDao;
    private TLongSet existing = new TLongHashSet();
    private final MetaInfoDao metaDao;


    public SqlLinksLoader(LocalLinkDao dao, LocalPageDao pageDao, MetaInfoDao metaDao, File file) {
        this.dao = dao;
        this.metaDao = metaDao;
        this.pageDao = pageDao;
        this.sqlDump = file;
        this.language = FileMatcher.LINK_SQL.getLanguage(file.getAbsolutePath());
    }

    public void load() throws DaoException {
        loadExisting();
        addNewLinks();
    }

    public void loadExisting() throws DaoException {
        existing.clear();
        for (LocalLink ll : dao.get(new DaoFilter().setLanguages(language))) {
            existing.add(ll.longHashCode());
        }
        LOG.info("Loaded " + existing.size() + " existing links");
    }

    public void addNewLinks() throws DaoException {
        int totalLinks = 0;
        int interestingLinks = 0;
        int newLinks = 0;
        for (Object[] row : new MySqlDumpParser().parse(sqlDump)) {

            if (++totalLinks % 100000 == 0) {
                LOG.info("Processed link " + totalLinks + ", found " + interestingLinks + " interesting and " + newLinks + " new");
            }

            Integer srcPageId = (Integer) row[0];
            Integer destNamespace = (Integer) row[1];
            String destTitle = (String) row[2];
            NameSpace ns = NameSpace.getNameSpaceByValue(destNamespace);

            // TODO: make this configurable
            if (ns == null || (ns != NameSpace.ARTICLE && ns != NameSpace.CATEGORY)) {
                continue;
            }
            if (srcPageId < 0 || StringUtils.isEmpty(destTitle)) {
                continue;
            }

            interestingLinks++;
            Title title = new Title(destTitle, LanguageInfo.getByLanguage(language));
            int destId = pageDao.getIdByTitle(title.getTitleStringWithoutNamespace(), language, ns);
            if (destId < 0) {
                // Handle red link
            } else {
                LocalLink ll = new LocalLink(language, "", srcPageId, destId,
                        true, -1, false, LocalLink.LocationType.NONE);
                if (!existing.contains(ll.longHashCode())) {
                    existing.add(ll.longHashCode());
                    newLinks++;
                    dao.save(ll);
                }
            }
        }
    }

    public static void main(String args[]) throws ClassNotFoundException, SQLException, IOException, ConfigurationException, DaoException {
        Options options = new Options();
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("drop-tables")
                        .withDescription("drop and recreate all tables")
                        .create("d"));
        options.addOption(
                new DefaultOptionBuilder()
                        .withLongOpt("max-links")
                        .hasArg()
                        .withDescription("maximum links per language")
                        .create("x"));
        EnvBuilder.addStandardOptions(options);

        CommandLineParser parser = new PosixParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println("Invalid option usage: " + e.getMessage());
            new HelpFormatter().printHelp("SqlLinksLoader", options);
            return;
        }

        EnvBuilder builder = new EnvBuilder(cmd);
        if (!builder.hasExplicitLanguageSet()) {
            builder.setUseDownloadedLanguages();
        }
        Env env = builder.build();
        Configurator conf = env.getConfigurator();
        List<File> paths;
        if (cmd.getArgList().isEmpty()) {
            paths = env.getFiles(FileMatcher.LINK_SQL);
        } else {
            paths = new ArrayList<File>();
            for (Object arg : cmd.getArgList()) {
                paths.add(new File((String)arg));
            }
        }

        final LocalLinkDao llDao = conf.get(LocalLinkDao.class);
        final LocalPageDao lpDao = conf.get(LocalPageDao.class);
        final MetaInfoDao metaDao = conf.get(MetaInfoDao.class);

        // TODO: run this in parallel
        if (cmd.hasOption("d")) {
            llDao.clear();
            metaDao.clear(LocalLink.class);
        }
        llDao.beginLoad();
        ParallelForEach.loop(paths,
            new Procedure<File>() {
                @Override
                public void call(File path) throws Exception {
                    final SqlLinksLoader loader = new SqlLinksLoader(llDao, lpDao, metaDao, path);
                    loader.load();
                }
            });
        llDao.endLoad();
    }

}
