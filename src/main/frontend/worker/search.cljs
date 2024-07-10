(ns frontend.worker.search
  "Full-text and fuzzy search"
  (:require [clojure.string :as string]
            [promesa.core :as p]
            [cljs-bean.core :as bean]
            ["fuse.js" :as fuse]
            [goog.object :as gobj]
            [datascript.core :as d]
            [frontend.search.fuzzy :as fuzzy]
            [frontend.worker.util :as worker-util]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.db.frontend.property :as db-property]))

;; TODO: use sqlite for fuzzy search
(defonce indices (atom nil))

(defn- add-blocks-fts-triggers!
  "Table bindings of blocks tables and the blocks FTS virtual tables"
  [db]
  (let [triggers [;; delete
                  "CREATE TRIGGER IF NOT EXISTS blocks_ad AFTER DELETE ON blocks
                  BEGIN
                      DELETE from blocks_fts where id = old.id;
                  END;"
                  ;; insert
                  "CREATE TRIGGER IF NOT EXISTS blocks_ai AFTER INSERT ON blocks
                  BEGIN
                      INSERT INTO blocks_fts (id, content, page)
                      VALUES (new.id, new.content, new.page);
                  END;"
                  ;; update
                  "CREATE TRIGGER IF NOT EXISTS blocks_au AFTER UPDATE ON blocks
                  BEGIN
                      DELETE from blocks_fts where id = old.id;
                      INSERT INTO blocks_fts (id, content, page)
                      VALUES (new.id, new.content, new.page);
                  END;"]]
    (doseq [trigger triggers]
      (.exec db trigger))))

(defn- create-blocks-table!
  [db]
  ;; id -> block uuid, page -> page uuid
  (.exec db "CREATE TABLE IF NOT EXISTS blocks (
                        id TEXT NOT NULL PRIMARY KEY,
                        content TEXT NOT NULL,
                        page TEXT)"))

(defn- create-blocks-fts-table!
  [db]
  (.exec db "CREATE VIRTUAL TABLE IF NOT EXISTS blocks_fts USING fts5(id, content, page)"))

(defn create-tables-and-triggers!
  "Open a SQLite db for search index"
  [db]
  (try
    (create-blocks-table! db)
    (create-blocks-fts-table! db)
    (add-blocks-fts-triggers! db)
    (catch :default e
      (prn "Failed to create tables and triggers")
      (js/console.error e)
      ;; FIXME:
      ;; (try
      ;;   ;; unlink db
      ;;   (catch :default e
      ;;     (js/console.error "cannot unlink search db:" e)))
      )))

(defn- clj-list->sql
  "Turn clojure list into SQL list
   '(1 2 3 4)
   ->
   \"('1','2','3','4')\""
  [ids]
  (str "(" (->> (map (fn [id] (str "'" id "'")) ids)
                (string/join ", ")) ")"))

(defn upsert-blocks!
  [^Object db blocks]
  (.transaction db (fn [tx]
                     (doseq [item blocks]
                       (if (and (common-util/uuid-string? (.-id item))
                                (common-util/uuid-string? (.-page item)))
                         (.exec tx #js {:sql "INSERT INTO blocks (id, content, page) VALUES ($id, $content, $page) ON CONFLICT (id) DO UPDATE SET (content, page) = ($content, $page)"
                                        :bind #js {:$id (.-id item)
                                                   :$content (.-content item)
                                                   :$page (.-page item)}})
                         (throw (ex-info "Search upsert-blocks wrong data: "
                                         (bean/->clj item))))))))

(defn delete-blocks!
  [db ids]
  (let [sql (str "DELETE from blocks WHERE id IN " (clj-list->sql ids))]
    (.exec db sql)))

(defonce max-snippet-length 250)

(defn- snippet-by
  [content length]
  (str (subs content 0 length) (when (> (count content) max-snippet-length) "...")))

(defn- get-snippet-result
  [snippet]
  (let [;; Cut snippet to limited size chars for non-matched results
        flag-highlight "$pfts_2lqh>$ "
        snippet (if (string/includes? snippet flag-highlight)
                  snippet
                  (snippet-by snippet max-snippet-length))]
    snippet))

(defn- search-blocks-aux
  [db sql input page limit]
  (try
    (p/let [result (if page
                     (.exec db #js {:sql sql
                                    :bind #js [page input limit]
                                    :rowMode "array"})
                     (.exec db #js {:sql sql
                                    :bind #js [input limit]
                                    :rowMode "array"}))
            blocks (bean/->clj result)]
      (map (fn [block]
             (update block 3 get-snippet-result)) blocks))
    (catch :default e
      (prn :debug "Search blocks failed: ")
      (js/console.error e))))

(defn- get-match-input
  [q]
  (let [match-input (-> q
                        (string/replace " and " " AND ")
                        (string/replace " & " " AND ")
                        (string/replace " or " " OR ")
                        (string/replace " | " " OR ")
                        (string/replace " not " " NOT "))]
    (if (not= q match-input)
      (string/replace match-input "," "")
      (str "\"" match-input "\""))))

(defn search-blocks
  ":page - the page to specifically search on"
  [db q {:keys [limit page]}]
  (when-not (string/blank? q)
    (p/let [match-input (get-match-input q)
            non-match-input (str "%" (string/replace q #"\s+" "%") "%")
            limit  (or limit 20)
            ;; https://www.sqlite.org/fts5.html#the_highlight_function
            ;; the 2nd column in blocks_fts (content)
            ;; pfts_2lqh is a key for retrieval
            ;; highlight and snippet only works for some matching with high rank
            snippet-aux "snippet(blocks_fts, 1, ' $pfts_2lqh>$ ', ' $<pfts_2lqh$ ', '...', 32)"
            select (str "select id, page, content, " snippet-aux " from blocks_fts where ")
            pg-sql (if page "page = ? and" "")
            match-sql (str select
                           pg-sql
                           " content match ? order by rank limit ?")
            non-match-sql (str select
                               pg-sql
                               " content like ? limit ?")
            matched-result (search-blocks-aux db match-sql match-input page limit)
            non-match-result (search-blocks-aux db non-match-sql non-match-input page limit)
            all-result (->> (concat matched-result non-match-result)
                            (map (fn [result]
                                   (let [[id page _content snippet] result]
                                     {:uuid id
                                      :content snippet
                                      :page page}))))]
      (->>
       all-result
       (common-util/distinct-by :uuid)
       (take limit)))))

(defn truncate-table!
  [db]
  (.exec db "delete from blocks")
  (.exec db "delete from blocks_fts"))

(defn- sanitize
  [content]
  (some-> content
          (worker-util/search-normalize true)))

(comment
  (defn- property-value-when-closed
    "Returns property value if the given entity is type 'closed value' or nil"
    [ent]
    (when (contains? (:block/type ent) "closed value")
      (:block/title ent))))

(comment
  (defn- get-db-properties-str
    "Similar to db-pu/readable-properties but with a focus on making property values searchable"
    [db properties]
    (->> properties
         (keep
          (fn [[k v]]
            (let [property (d/entity db k)
                  values
                  (->> (if (set? v) v #{v})
                       (map (fn [val]
                              (if (= :db.type/ref (:db/valueType property))
                                (let [e (d/entity db (:db/id val))
                                      value (or
                                           ;; closed value
                                             (property-value-when-closed e)
                                           ;; :page or :date properties
                                             (:block/title e)
                                             ;; first child
                                             (let [parent-id (:db/id e)]
                                               (:block/title (ldb/get-first-child db parent-id))))]
                                  value)
                                val)))
                       (remove string/blank?))
                  hide? (get-in property [:block/schema :hide?])]
              (when (and (not hide?) (seq values))
                (str (:block/title property)
                     ": "
                     (string/join "; " values))))))
         (string/join ", "))))

(defn block->index
  "Convert a block to the index for searching"
  [{:block/keys [uuid page title format] :as block}]
  (when-not (or
             (:block/name block)
             (ldb/closed-value? block)
             (and (string? title) (> (count title) 10000))
             (string/blank? title))        ; empty page or block
      ;; Should properties be included in the search indice?
      ;; It could slow down the search indexing, also it can be confusing
      ;; if the showing properties are not useful to users.
      ;; (let [content (if (and db-based? (seq (:block/properties block)))
    ;;                 (str content (when (not= content "") "\n") (get-db-properties-str db properties))
      ;;                 content)])
    (when uuid
      {:id (str uuid)
       :page (str (or (:block/uuid page) uuid))
       :content (sanitize title)
       :format format})))


(defn get-all-block-contents
  [db]
  (when db
    (->> (d/datoms db :avet :block/uuid)
         (map :v)
         (keep #(d/entity db [:block/uuid %])))))

(defn build-blocks-indice
  [db]
  (->> (get-all-block-contents db)
       (keep block->index)
       (bean/->js)))

(defn original-page-name->index
  [p]
  (when p
    {:id (str (:block/uuid p))
     :name (:block/name p)
     :built-in? (boolean (db-property/property-value-content (:logseq.property/built-in? p)))
     :title (:block/title p)}))

(defn- hidden-page?
  [page]
  (when page
    (if (string? page)
      (string/starts-with? page "$$$")
      (contains? (set (:block/type page)) "hidden"))))

(defn get-all-pages
  [db]
  (let [page-datoms (d/datoms db :avet :block/name)
        pages (map (fn [d] (d/entity db (:e d))) page-datoms)]
    (remove (fn [p] (hidden-page? (:block/name p))) pages)))

(defn build-page-indice
  "Build a page title indice from scratch.
   Incremental page title indice is implemented in frontend.search.sync-search-indice!
   Rename from the page indice since 10.25.2022, since this is only used for page title search.
   From now on, page indice is talking about page content search."
  [repo db]
  (let [pages (->> (get-all-pages db)
                   (map original-page-name->index)
                   (bean/->js))
        indice (fuse. pages
                      (clj->js {:keys ["title"]
                                :shouldSort true
                                :tokenize true
                                :distance 1024
                                :threshold 0.5 ;; search for 50% match from the start
                                :minMatchCharLength 1}))]
    (swap! indices assoc-in [repo :pages] indice)
    indice))

(defn- get-blocks-from-datoms-impl
  [repo {:keys [db-after db-before]} datoms]
  (when (seq datoms)
    (let [blocks-to-add-set (->> (filter :added datoms)
                                 (map :e)
                                 (set))
          blocks-to-remove-set (->> (remove :added datoms)
                                    (filter #(= :block/uuid (:a %)))
                                    (map :e)
                                    (set))
          blocks-to-add-set' (if (and (sqlite-util/db-based-graph? repo) (seq blocks-to-add-set))
                               (->> blocks-to-add-set
                                    (mapcat (fn [id] (map :db/id (:block/_refs (d/entity db-after id)))))
                                    (concat blocks-to-add-set)
                                    set)
                               blocks-to-add-set)]
      {:blocks-to-remove     (->>
                              (keep #(d/entity db-before %) blocks-to-remove-set)
                              (remove hidden-page?))
       :blocks-to-add        (->>
                              (keep #(d/entity db-after %) blocks-to-add-set')
                              (remove hidden-page?))})))

(defn- get-direct-blocks-and-pages
  [repo tx-report]
  (let [data (:tx-data tx-report)
        datoms (filter
                (fn [datom]
                  ;; Capture any direct change on page display title, page ref or block content
                  (contains? #{:block/uuid :block/name :block/title :block/properties :block/schema} (:a datom)))
                data)]
    (when (seq datoms)
      (get-blocks-from-datoms-impl repo tx-report datoms))))

(defn sync-search-indice
  [repo tx-report]
  (let [{:keys [blocks-to-add blocks-to-remove]} (get-direct-blocks-and-pages repo tx-report)]
    ;; update page title indice
    (let [pages-to-add (filter :block/name blocks-to-add)
          pages-to-remove (filter :block/name blocks-to-remove)]
      (when (or (seq pages-to-add) (seq pages-to-remove))
        (swap! indices update-in [repo :pages]
               (fn [indice]
                 (when indice
                   (doseq [page-entity pages-to-remove]
                     (.remove indice (fn [page] (= (str (:block/uuid page-entity)) (gobj/get page "id")))))
                   (doseq [page pages-to-add]
                     (.remove indice (fn [p] (= (str (:block/uuid page)) (gobj/get p "id"))))
                     (.add indice (bean/->js (original-page-name->index page))))
                   indice)))))

    ;; update block indice
    (when (or (seq blocks-to-add) (seq blocks-to-remove))
      (let [blocks-to-add (keep block->index blocks-to-add)
            blocks-to-remove (set (map (comp str :block/uuid) blocks-to-remove))]
        {:blocks-to-remove-set blocks-to-remove
         :blocks-to-add        blocks-to-add}))))

(defn exact-matched?
  "Check if two strings points toward same search result"
  [q match]
  (when (and (string? q) (string? match))
    (boolean
     (reduce
      (fn [coll char]
        (let [coll' (drop-while #(not= char %) coll)]
          (if (seq coll')
            (rest coll')
            (reduced false))))
      (seq (worker-util/search-normalize match true))
      (seq (worker-util/search-normalize q true))))))

(defn page-search
  "Return a list of page names that match the query. Takes the following
  options:
   * :limit - Number of pages to limit search results. Defaults to 100
   * :built-in?  - Whether to return built-in pages for db graphs. Defaults to true"
  [repo db q {:keys [limit built-in?]
              :or {limit 100
                   built-in? true}}]
  (when repo
    (let [q (worker-util/search-normalize q true)
          q (fuzzy/clean-str q)
          q (if (= \# (first q)) (subs q 1) q)]
      (when-not (string/blank? q)
        (let [indice (or (get-in @indices [repo :pages])
                         (build-page-indice repo db))
              result (cond->>
                      (->> (.search indice q (clj->js {:limit limit}))
                           (bean/->clj))

                       (and (sqlite-util/db-based-graph? repo) (= false built-in?))
                       (remove #(get-in % [:item :built-in?])))]
          (->> result
               (keep
                (fn [{:keys [item]}]
                  {:id (:id item)
                   :title (:title item)}))
               (distinct)
               (filter (fn [{:keys [title]}]
                         (exact-matched? q title)))
               bean/->js))))))
