package org.wikibrain.core.dao;

import org.wikibrain.core.lang.Language;
import org.wikibrain.core.model.LocalArticle;
import org.wikibrain.core.model.Title;

import java.util.Collection;
import java.util.Map;

public interface LocalArticleDao extends LocalPageDao<LocalArticle>  {

    public abstract LocalArticle getByTitle(Language language, Title title) throws DaoException;

    public abstract Map<Title, LocalArticle> getByTitles(Language language, Collection<Title> titles) throws DaoException;

}
