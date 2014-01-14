CREATE TABLE IF NOT EXISTS WIKIDATA_ITEM_LABELS (
  ITEM_ID int NOT NULL,
  LANG_ID smallint  NOT NULL,
  LABEL TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS WIKIDATA_ITEM_DESCRIPTIONS (
  ITEM_ID int NOT NULL,
  LANG_ID smallint  NOT NULL,
  DESCRIPTION TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS WIKIDATA_ITEM_ALIASES (
  ITEM_ID int NOT NULL,
  LANG_ID smallint  NOT NULL,
  ALIAS TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS WIKIDATA_STATEMENT (

  --   Unique identifier
  ID VARCHAR(255) NOT NULL,

  --  id of item associated with the property - a "Q" would typically prefix it on Wikipedia
  ITEM_ID int NOT NULL,

  -- Property id (starts with a p)
  PROP_ID int NOT NULL,

  -- Property value
  VAL_TYPE VARCHAR(50) NOT NULL,
  VAL_STR TEXT NOT NULL,

  -- Property rank: 0 = deprecated, 1 = normal, 2 = preferred
  RANK SMALLINT NOT NULL,
);