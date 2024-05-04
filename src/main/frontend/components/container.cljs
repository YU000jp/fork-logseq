(ns frontend.components.container
  (:require [cljs-drag-n-drop.core :as dnd]
            [clojure.string :as string]
            [frontend.version :refer [version]]
            [frontend.components.find-in-page :as find-in-page]
            [frontend.components.header :as header]
            [frontend.components.journal :as journal]
            [frontend.components.onboarding :as onboarding]
            [frontend.components.plugins :as plugins]
            ;; [frontend.components.repo :as repo]
            ;; [frontend.components.hierarchy :as hierarchy]
            [frontend.components.cmdk :as cmdk]
            [frontend.date :as date]
            [frontend.components.right-sidebar :as right-sidebar]
            [frontend.components.select :as select]
            [frontend.components.theme :as theme]
            [frontend.components.page :as com-page]
            [frontend.components.block :as com-block]
            [frontend.components.widgets :as widgets]
            [frontend.components.handbooks :as handbooks]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [electron.ipc :as ipc]
            [frontend.db-mixins :as db-mixins]
            [frontend.db.model :as db-model]
            [frontend.extensions.pdf.utils :as pdf-utils]
            [frontend.storage :as storage]
            [frontend.extensions.srs :as srs]
            [frontend.handler.common :as common-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.page :as page-handler]
            [frontend.util.page :as page-util]
            [frontend.handler.route :as route-handler]
            [frontend.handler.user :as user-handler]
            [frontend.handler.whiteboard :as whiteboard-handler]
            [frontend.mixins :as mixins]
            [frontend.mobile.action-bar :as action-bar]
            [frontend.mobile.footer :as footer]
            [frontend.mobile.mobile-bar :refer [mobile-bar]]
            [frontend.mobile.util :as mobile-util]
            [frontend.modules.shortcut.data-helper :as shortcut-dh]
            [frontend.modules.shortcut.utils :as shortcut-utils]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [logseq.shui.ui :as shui]
            [logseq.shui.toaster.core :as shui-toaster]
            [logseq.shui.dialog.core :as shui-dialog]
            [logseq.graph-parser.util.page-ref :as page-ref]
            
            [frontend.util :as util]
            [frontend.util.cursor :as cursor]
            [frontend.components.window-controls :as window-controls]
            [medley.core :as medley]
            [goog.dom :as gdom]
            [goog.object :as gobj]
            [logseq.common.path :as path]
            [react-draggable]
            [reitit.frontend.easy :as rfe]
            [rum.core :as rum]))

(rum/defc nav-content-item < rum/reactive
  [name {:keys [class count]} child]
  (let [collapsed? (state/sub [:ui/navigation-item-collapsed? class])]
    [:div.nav-content-item
     {:class (util/classnames [class {:is-expand (not collapsed?)
                                      :has-children (and (number? count) (> count 0))}])}
     [:div.nav-content-item-inner
      [:div.header.items-center
       {:on-click (fn [^js/MouseEvent _e]
                    (state/toggle-navigation-item-collapsed! class))}
       [:div.a name]
       [:div.b (ui/icon "chevron-left" {:class "more" :size 14})]]
      (when child [:div.bd child])]]))

(defn- delta-y
  [e]
  (when-let [target (.. e -target)]
    (let [rect (.. target getBoundingClientRect)]
      (- (.. e -pageY) (.. rect -top)))))

(defn- move-up?
  [e]
  (let [delta (delta-y e)]
    (< delta 14)))

(rum/defc page-name-dot
  [name icon recent?]
  (let [original-name (db-model/get-page-original-name name)
        whiteboard-page? (db-model/whiteboard-page? name)
        untitled? (db-model/untitled-page? name)
        name (util/safe-page-name-sanity-lc name)
        repo (state/get-current-repo)
        file-rpath (when (util/electron?) (page-util/get-page-file-rpath name))
        source-page (db-model/get-alias-source-page repo name)
        ctx-icon #(shui/tabler-icon %1 {:class "scale-90 pr-1 opacity-80"})
        open-in-sidebar #(when-let [page-entity (and (not whiteboard-page?)
                                                  (if (empty? source-page)
                                                    (db/entity [:block/name name]) source-page))]
                           (state/sidebar-add-block!
                            repo
                            (:db/id page-entity)
                            :page))
        x-menu-content (fn [type opts]
                         (let [dropdown? (= type :dropdown)
                               x-menu-content (if dropdown? shui/dropdown-menu-content shui/context-menu-content)
                               x-menu-item (if dropdown? shui/dropdown-menu-item shui/context-menu-item)
                               x-menu-shortcut (if dropdown? shui/dropdown-menu-shortcut shui/context-menu-shortcut)]
                           (x-menu-content
                             (merge {:class "w-60"} opts)
                             (when-not recent?
                               (x-menu-item
                                 {:on-click #(page-handler/unfavorite-page! original-name)}
                                 (ctx-icon "star-off")
                                 (t :page/unfavorite)
                                 (x-menu-shortcut (when-let [binding (shortcut-dh/shortcut-binding :command/toggle-favorite)]
                                                    (some-> binding
                                                            (first)
                                                            (shortcut-utils/decorate-binding))))))
                             (when-let [page-fpath (and (util/electron?) file-rpath
                                                     (config/get-repo-fpath repo file-rpath))]
                               [:<>
                                (x-menu-item
                                  {:on-click #(ipc/ipc :openFileInFolder page-fpath)}
                                  (ctx-icon "folder")
                                  (t :page/open-in-finder))

                                (x-menu-item
                                  {:on-click #(js/window.apis.openPath page-fpath)}
                                  (ctx-icon "file")
                                  (t :page/open-with-default-app))])

                             (x-menu-item
                               {:on-click open-in-sidebar}
                               (ctx-icon "layout-sidebar-right")
                               (t :content/open-in-sidebar)
                               (x-menu-shortcut (shortcut-utils/decorate-binding "shift+click"))))))]

    ;; TODO: move to standalone component
    (shui/context-menu
      (shui/context-menu-trigger
        [:a.flex.items-center.justify-between.relative.group
         {:on-click
          (fn [e]
            (let [name (if (empty? source-page) name (:block/name source-page))]
              (if (gobj/get e "shiftKey")
                (open-in-sidebar)
                (if whiteboard-page?
                  (route-handler/redirect-to-whiteboard! name {:click-from-recent? recent?})
                  (route-handler/redirect-to-page! name {:click-from-recent? recent?})))))}
         [:span.page-icon.ml-3.justify-center (if whiteboard-page? (ui/icon "whiteboard" {:extension? true}) icon)]
         [:span.page-title {:class (when untitled? "opacity-50")}
          (if untitled? (t :untitled)
                        (pdf-utils/fix-local-asset-pagename original-name))]

         ;; dots trigger
         (shui/dropdown-menu
           (shui/dropdown-menu-trigger
             (shui/button
               {:size     :sm
                :variant  :ghost
                :class    "absolute right-2 top-0 px-1.5 scale-75 opacity-30 hidden group-hover:block hover:opacity-80 active:opacity-100"
                :on-click #(util/stop %)}
               [:i.relative {:style {:top "1px"}} (shui/tabler-icon "dots")]))
           ;; menu content
           (x-menu-content :dropdown {:align "start"}))]

        ;; menu content
        (x-menu-content :context nil)))))

(defn get-page-icon [page-entity]
  (let [default-icon (ui/icon "page" {:extension? true})
        from-properties (get-in (into {} page-entity) [:block/properties :icon])]
    (or
     (when (not= from-properties "") from-properties)
     default-icon))) ;; Fall back to default if icon is undefined or empty

(rum/defcs favorite-item <
  (rum/local nil ::up?)
  (rum/local nil ::dragging-over)
  [state _t name icon]
  (let [up? (get state ::up?)
        dragging-over (get state ::dragging-over)
        target (state/sub :favorites/dragging)]
    [:li.favorite-item
     {:key name
      :title name
      :data-ref name
      :class (if (and target @dragging-over (not= target @dragging-over))
               "dragging-target"
               "")
      :draggable true
      :on-drag-start (fn [event]
                       (editor-handler/block->data-transfer! name event)
                       (state/set-state! :favorites/dragging name))
      :on-drag-over (fn [e]
                      (util/stop e)
                      (reset! dragging-over name)
                      (when-not (= name (get @state/state :favorites/dragging))
                        (reset! up? (move-up? e))))
      :on-drag-leave (fn [_e]
                       (reset! dragging-over nil))
      :on-drop (fn [e]
                 (page-handler/reorder-favorites! {:to name
                                                   :up? (move-up? e)})
                 (reset! up? nil)
                 (reset! dragging-over nil))}
     (page-name-dot name icon false)]))

(rum/defc favorites < rum/reactive
  [t]
  (let [favorites (->> (:favorites (state/sub-config))
                       (remove string/blank?)
                       (filter string?)
                       (mapv util/safe-page-name-sanity-lc)
                       (distinct))
        favorite-entities (->> favorites
                               (mapv #(db/entity [:block/name %]))
                               (remove nil?))]
    (nav-content-item
     [:a.flex.items-center.rounded-md.wrap-th
      (ui/icon "star" {:size 26})
      [:span.flex-1.ml-2.text-l
       (t :left-side-bar/nav-favorites)]]

     {:class "favorites"
      :count (count favorite-entities)
      :edit-fn
      (fn [e]
        (rfe/push-state :page {:name "Favorites"})
        (util/stop e))}
     (when (seq favorite-entities)
       [:ul.favorites.text-sm
        (for [entity favorite-entities]
          (let [icon (get-page-icon entity)]
            (favorite-item t (:block/name entity) icon)))]))))

(rum/defc recent-pages < rum/reactive db-mixins/query
  [t]
  (let [pages (->> (db/sub-key-value :recent/pages)
                   (remove string/blank?)
                   (filter string?)
                   (map (fn [page] {:lowercase (util/safe-page-name-sanity-lc page)
                                    :page page}))
                   (util/distinct-by :lowercase)
                   (map :page))]
    (nav-content-item
     [:a.flex.items-center.rounded-md.wrap-th
      (ui/icon "history" {:size 26})
      [:span.flex-1.ml-2.text-l
       (t :left-side-bar/nav-recent-pages)]]

     {:class "recent"
      :count (count pages)}

     [:ul.text-sm
      (for [name pages]
        (when-let [entity (db/entity [:block/name (util/safe-page-name-sanity-lc name)])]
          [:li.recent-item.select-none
           {:key name
            :title name
            :draggable true
            :on-drag-start (fn [event] (editor-handler/block->data-transfer! name event))
            :data-ref name}
           (page-name-dot name (get-page-icon entity) true)]))])))

(rum/defcs flashcards < db-mixins/query rum/reactive
  {:did-mount (fn [state]
                (srs/update-cards-due-count!)
                state)}
  [_state srs-open?]
  (let [num (state/sub :srs/cards-due-count)]
    [:a.item.group.flex.items-center.px-2.py-2.text-sm.font-medium.rounded-md
     {:class (util/classnames [{:active srs-open?}])
      :on-click #(do
                   (srs/update-cards-due-count!)
                   (state/pub-event! [:modal/show-cards]))}
     (ui/icon "infinity")
     [:span.flex-1 (t :right-side-bar/flashcards)]
     [:span.ml-1 (ui/render-keyboard-shortcut
                  (ui/keyboard-shortcut-from-config :go/flashcards
                                                    {:pick-first? true}))]
     (when (and num (not (zero? num)))
       [:span.ml-1.inline-block.py-0.5.px-3.text-xs.font-medium.rounded-full.fade-in num])]))

(defn get-default-home-if-valid
  []
  (when-let [default-home (state/get-default-home)]
    (let [page (:page default-home)
          page (when (and (string? page)
                          (not (string/blank? page)))
                 (db/entity [:block/name (util/safe-page-name-sanity-lc page)]))]
      (if page
        default-home
        (dissoc default-home :page)))))

(defn sidebar-item
  [{:keys [on-click-handler class title icon icon-extension? active href shortcut]}]
  [:div
   {:class class}
   [:a.item.group.flex.items-center.rounded-md
    {:on-click on-click-handler
     :class (when active "active")
     :href href}
    (ui/icon (str icon) {:extension? icon-extension?})
    [:span.flex-1 title]
    (when shortcut
      [:span.ml-1 (ui/render-keyboard-shortcut (ui/keyboard-shortcut-from-config shortcut))])]])

(defn close-sidebar-on-mobile!
  []
  (and (util/sm-breakpoint?)
       (state/toggle-left-sidebar!)))

(defn create-dropdown
  [enable-whiteboards? page-name]
  (ui/dropdown-with-links
   (fn [{:keys [toggle-fn]}]
     [:button#create-button
      {:on-click toggle-fn}
      [:<>
       (ui/icon "plus")]])
   (->>
    [{:title (t :left-side-bar/new-page)
      :class "new-page-link"
      :options {:on-click (fn [e]
                            [(close-sidebar-on-mobile!)
                             (if (util/shift-key? e)
                               (state/sidebar-add-block! (state/get-current-repo) "" :search)
                               [(state/set-modal! cmdk/cmdk-modal
                                                  {:fullscreen? false
                                                   :close-btn?  false
                                                   :panel?      false
                                                   :label "ls-modal-search"})])
                               (state/set-search-mode! :pages)])
                :shortcut (ui/keyboard-shortcut-from-config :go/search)}
      :icon (ui/type-icon {:name "new-page"
                           :class "highlight"
                           :extension? true})}
     (when page-name
       {:title (t :left-side-bar/new-sub-page)
        :class "new-page-link"
        :options {:on-click (fn [e]
                              (let [sub-page (str (db-model/get-page-original-name page-name) "/")]
                                [(close-sidebar-on-mobile!)
                                 (if (util/shift-key? e)
                                   (state/sidebar-add-block! (state/get-current-repo) sub-page :search)
                                   [(state/set-modal! cmdk/cmdk-modal
                                                      {:fullscreen? false
                                                       :close-btn?  false
                                                       :panel?      true
                                                       :label "ls-modal-search"})
                                    ((js/setTimeout #(let [input (gdom/getElement "search")];; ページ名とスラッシュを自動入力
                                                       (set! (.-value input) sub-page)
                                                       (.focus input))
                                                    100))])
                                 (state/set-search-mode! :pages)]))}
        :icon (ui/type-icon {:name "new-page"
                             :class "highlight"
                             :extension? true})})
     (when enable-whiteboards?
       {:title (t :left-side-bar/new-whiteboard)
        :class "new-whiteboard-link"
        :options {:on-click #(do (close-sidebar-on-mobile!)
                                 (whiteboard-handler/create-new-whiteboard-and-redirect!))
                  :shortcut (ui/keyboard-shortcut-from-config :editor/new-whiteboard)}
        :icon (ui/type-icon {:name "new-whiteboard"
                             :class "highlight"
                             :extension? true})})])
   {}))

(rum/defc ^:large-vars/cleanup-todo sidebar-nav
  [route-match close-modal-fn left-sidebar-open? enable-whiteboards? srs-open?
   *closing? close-signal touching-x-offset]
  (let [[local-closing? set-local-closing?] (rum/use-state false)
        [el-rect set-el-rect!] (rum/use-state nil)
        ref-el              (rum/use-ref nil)
        ref-open?           (rum/use-ref left-sidebar-open?)
        default-home        (get-default-home-if-valid)
        route-name          (get-in route-match [:data :name])
        on-contents-scroll  #(when-let [^js el (.-target %)]
                               (let [top  (.-scrollTop el)
                                     cls  (.-classList el)
                                     cls' "is-scrolled"]
                                 (if (> top 2)
                                   (.add cls cls')
                                   (.remove cls cls'))))
        close-fn            #(set-local-closing? true)
        touching-x-offset (when (number? touching-x-offset)
                            (if-not left-sidebar-open?
                              (when (> touching-x-offset 0)
                                (min touching-x-offset (:width el-rect)))
                              (when (< touching-x-offset 0)
                                (max touching-x-offset (- 0 (:width el-rect))))))
        offset-ratio (and (number? touching-x-offset)
                          (some->> (:width el-rect)
                                   (/ touching-x-offset)))
        repo               (state/get-current-repo)
        page-name (state/get-current-page)]

    (rum/use-effect!
     #(js/setTimeout
       (fn [] (some-> (rum/deref ref-el)
                      (.getBoundingClientRect)
                      (.toJSON)
                      (js->clj :keywordize-keys true)
                      (set-el-rect!)))
       16)
     [])

    (rum/use-layout-effect!
     (fn []
       (when (and (rum/deref ref-open?) local-closing?)
         (reset! *closing? true))
       (rum/set-ref! ref-open? left-sidebar-open?)
       #())
     [local-closing? left-sidebar-open?])

    (rum/use-effect!
     (fn []
       (when-not (neg? close-signal)
         (close-fn)))
     [close-signal])

    [:<>
     [:div.left-sidebar-inner.flex-1.flex.flex-col.min-h-0
      {:ref               ref-el
       :style             (cond-> {}
                            (and (number? offset-ratio)
                                 (> touching-x-offset 0))
                            (assoc :transform (str "translate3d(calc(" touching-x-offset "px - 100%), 0, 0)"))

                            (and (number? offset-ratio)
                                 (< touching-x-offset 0))
                            (assoc :transform (str "translate3d(" (* offset-ratio 100) "%, 0, 0)")))
       :on-transition-end (fn []
                            (when local-closing?
                              (reset! *closing? false)
                              (set-local-closing? false)
                              (close-modal-fn)))
       :on-click          #(when-let [^js target (and (util/sm-breakpoint?) (.-target %))]
                             (when (some (fn [sel] (boolean (.closest target sel)))
                                         [".favorites .bd" ".recent .bd" ".dropdown-wrapper" ".nav-header"])
                               (close-fn)))}

      [:div.flex.flex-col.wrap.gap-1.relative
       ;; temporarily remove fake hamburger menu
       ;(when (mobile-util/native-platform?)
       ;  [:div.fake-bar.absolute
       ;   [:button
       ;    {:on-click state/toggle-left-sidebar!}
       ;    (ui/icon "menu-2" {:size ui/icon-size})]])

       [:nav.px-4.flex.flex-col.gap-1.cp__menubar-repos
        {:aria-label "Navigation menu"}

        [:div.nav-header.flex.flex-col.mt-2.mb-4
         (let [page (:page default-home)]
           (when (and page (not (state/enable-journals? repo)))
             (sidebar-item
              {:class "home-nav"
               :title page
               :on-click-handler route-handler/redirect-to-home!
               :active (and (not srs-open?)
                            (= route-name :page)
                            (= page (get-in route-match [:path-params :name])))
               :icon "home"
               :shortcut :go/home})))

         (sidebar-item
          {:class "journals-nav"
           :active (and (not srs-open?)
                        (or (= route-name :all-journals) (= route-name :home)))
           :title (t :left-side-bar/journals)
           :on-click-handler (fn [e]
                               (cond
                                 (util/shift-key? e) (route-handler/sidebar-journals!)
                                 (util/alt-key? e) (route-handler/redirect-to-page! (date/today))
                                 :else
                                 (route-handler/redirect-to-home!)))
           :icon "calendar-time"
           :shortcut :go/journals})

         (when enable-whiteboards?
           (sidebar-item
            {:class "whiteboard"
             :title (t :right-side-bar/whiteboards)
             :href (rfe/href :whiteboards)
             :on-click-handler (fn [_e] (whiteboard-handler/onboarding-show))
             :active (and (not srs-open?) (#{:whiteboard :whiteboards} route-name))
             :icon "whiteboard"
             :icon-extension? true
             :shortcut :go/whiteboards}))

         (when (state/enable-flashcards? repo)
           [:div.flashcards-nav
            (flashcards srs-open?)])

        ;;  (sidebar-item
        ;;   {:class "graph-view-nav"
        ;;    :title (t :right-side-bar/graph-view)
        ;;    :href (rfe/href :graph)
        ;;    :active (and (not srs-open?) (= route-name :graph))
        ;;    :icon "network"
        ;;    :shortcut :go/graph-view})

        ;;  (sidebar-item
        ;;   {:class "all-pages-nav"
        ;;    :title (t :right-side-bar/all-pages)
        ;;    :href (rfe/href :all-pages)
        ;;    :active (and (not srs-open?) (= route-name :all-pages))
        ;;    :icon "files"})
         ]]

       [:div.nav-contents-container.flex.flex-col.gap-1.pt-1
        {:on-scroll on-contents-scroll}
        (favorites t)]
       [:div.nav-contents-container.flex.flex-col.gap-1.pt-1
        {:on-scroll on-contents-scroll}
        (when (not config/publishing?)
          (recent-pages t))]


       (if page-name
         [:div.nav-contents-container.flex.flex-col.gap-1.pt-1
          {:on-scroll on-contents-scroll}
            ;; (hierarchy/structures page-name) 
            ;; hierarchy
          (let [namespace page-name]
            (when-not (string/blank? namespace)
              (let [namespace (string/lower-case (page-ref/get-page-name! namespace))
                    children (db-model/get-namespace-hierarchy (state/get-current-repo) namespace)]
                (com-block/namespace-hierarchy {} namespace children false))))]
         [:div.nav-contents-container.flex.flex-col.gap-1.pt-1
          ""])


       (when page-name
         [:div.nav-contents-container.flex.flex-col.gap-1.pt-1
          {:on-scroll on-contents-scroll}
            ;; tagged pages
          (com-page/tagged-pages repo page-name)])

       [:footer.px-2.mt-1.create
        (when-not config/publishing?
          (create-dropdown enable-whiteboards? page-name))]]]
     
     [:span.shade-mask
      (cond-> {:on-click close-fn}
        (number? offset-ratio)
        (assoc :style {:opacity (cond-> offset-ratio
                                  (neg? offset-ratio)
                                  (+ 1))}))]]))

(rum/defc sidebar-resizer
  []
  (let [*el-ref (rum/use-ref nil)
        ^js el-doc js/document.documentElement
        adjust-size! (fn [width]
                       (.setProperty (.-style el-doc) "--ls-left-sidebar-width" width)
                       (storage/set :ls-left-sidebar-width width))]

    ;; restore size
    (rum/use-layout-effect!
      (fn []
        (when-let [width (storage/get :ls-left-sidebar-width)]
          (.setProperty (.-style el-doc) "--ls-left-sidebar-width" width)))
      [])

    ;; draggable handler
    (rum/use-effect!
      (fn []
        (when-let [el (and (fn? js/window.interact) (rum/deref *el-ref))]
          (let [^js sidebar-el (.querySelector el-doc "#left-sidebar")]
            (-> (js/interact el)
              (.draggable
                #js {:listeners
                     #js {:move (fn [^js/MouseEvent e]
                                  (when-let [offset (.-left (.-rect e))]
                                    (let [width (.toFixed (max (min offset 460) 240) 2)]
                                      (adjust-size! (str width "px")))))}})
              (.styleCursor false)
              (.on "dragstart" (fn []
                                 (.. sidebar-el -classList (add "is-resizing"))
                                 (.. el-doc -classList (add "is-resizing-buf"))))
              (.on "dragend" (fn []
                               (.. sidebar-el -classList (remove "is-resizing"))
                               (.. el-doc -classList (remove "is-resizing-buf"))))))
          #()))
      [])
    [:span.left-sidebar-resizer {:ref *el-ref}]))

(rum/defcs left-sidebar < rum/reactive
  (rum/local false ::closing?)
  (rum/local -1 ::close-signal)
  (rum/local nil ::touch-state)
  [s {:keys [left-sidebar-open? route-match]}]
  (let [close-fn             #(state/set-left-sidebar-open! false)
        *closing?            (::closing? s)
        *touch-state         (::touch-state s)
        *close-signal        (::close-signal s)
        enable-whiteboards?  (state/enable-whiteboards?)
        touch-point-fn       (fn [^js e] (some-> (gobj/get e "touches") (aget 0) (#(hash-map :x (.-clientX %) :y (.-clientY %)))))
        srs-open?            (= :srs (state/sub :modal/id))
        touching-x-offset    (and (some-> @*touch-state :after)
                                  (some->> @*touch-state
                                           ((juxt :after :before))
                                           (map :x) (apply -)))
        touch-pending?       (> (abs touching-x-offset) 20)]

    [:div#left-sidebar.cp__sidebar-left-layout
     {:class (util/classnames [{:is-open     left-sidebar-open?
                                :is-closing  @*closing?
                                :is-touching touch-pending?}])
      :on-touch-start
      (fn [^js e]
        (reset! *touch-state {:before (touch-point-fn e)}))
      :on-touch-move
      (fn [^js e]
        (when @*touch-state
          (some-> *touch-state (swap! assoc :after (touch-point-fn e)))))
      :on-touch-end
      (fn []
        (when touch-pending?
          (cond
            (and (not left-sidebar-open?) (> touching-x-offset 40))
            (state/set-left-sidebar-open! true)

            (and left-sidebar-open? (< touching-x-offset -30))
            (reset! *close-signal (inc @*close-signal))))
        (reset! *touch-state nil))}

     ;; sidebar contents
     (sidebar-nav route-match close-fn left-sidebar-open? enable-whiteboards? srs-open? *closing?
       @*close-signal (and touch-pending? touching-x-offset))
     ;; resizer
     (sidebar-resizer)]))

(rum/defc recording-bar
  []
  [:> react-draggable
   {:onStart (fn [_event]
               (when-let [pos (some-> (state/get-input) cursor/pos)]
                 (state/set-editor-last-pos! pos)))
    :onStop (fn [_event]
              (when-let [block (get-in @state/state [:editor/block :block/uuid])]
                (editor-handler/edit-block! block :max (:block/uuid block))
                (when-let [input (state/get-input)]
                  (when-let [saved-cursor (state/get-editor-last-pos)]
                    (cursor/move-cursor-to input saved-cursor)))))}
   [:div#audio-record-toolbar
    {:style {:bottom (+ @util/keyboard-height 45)}}
    (footer/audio-record-cp)]])

(rum/defc main <
  {:did-mount (fn [state]
                (when-let [element (gdom/getElement "app-container")]
                  (dnd/subscribe!
                   element
                   :upload-files
                   {:drop (fn [_e files]
                            (when-let [id (state/get-edit-input-id)]
                              (let [format (:block/format (state/get-edit-block))]
                                (editor-handler/upload-asset id files format editor-handler/*asset-uploading? true))))})
                  (common-handler/listen-to-scroll! element)
                  (when (:margin-less-pages? (first (:rum/args state))) ;; makes sure full screen pages displaying without scrollbar
                    (set! (.. element -scrollTop) 0)))
                state)
   :will-unmount (fn [state]
                   (when-let [el (gdom/getElement "app-container")]
                     (dnd/unsubscribe! el :upload-files))
                   state)}
  [{:keys [route-match margin-less-pages? route-name indexeddb-support? db-restoring? main-content show-action-bar? show-recording-bar?]}]
  (let [left-sidebar-open?   (state/sub :ui/left-sidebar-open?)
        onboarding-and-home? (and (or (nil? (state/get-current-repo)) (config/demo-graph?))
                                  (not config/publishing?)
                                  (= :home route-name))
        margin-less-pages?   (or (and (mobile-util/native-platform?) onboarding-and-home?) margin-less-pages?)]
    [:div#main-container.cp__sidebar-main-layout.flex-1.flex
     {:class (util/classnames [{:is-left-sidebar-open left-sidebar-open?}])}

     ;; desktop left sidebar layout
     (left-sidebar {:left-sidebar-open? left-sidebar-open?
                    :route-match        route-match})

     [:div#main-content-container.scrollbar-spacing.w-full.flex.justify-center.flex-row.outline-none.relative

      {:tabIndex "-1"
       :data-is-margin-less-pages margin-less-pages?}

      (when show-action-bar?
        (action-bar/action-bar))

      [:div.cp__sidebar-main-content
       {:data-is-margin-less-pages margin-less-pages?
        :data-is-full-width        (or margin-less-pages?
                                       (contains? #{:all-files :all-pages :my-publishing} route-name))}

       (when show-recording-bar?
         (recording-bar))

       (mobile-bar)
       (footer/footer)

       (when (and (not (mobile-util/native-platform?))
                  (contains? #{:page :home} route-name))
         (widgets/demo-graph-alert))

       (cond
         (not indexeddb-support?)
         nil

         db-restoring?
         [:div.mt-20
          [:div.ls-center
           (ui/loading)]]

         :else
         [:div
          {:class (if (or onboarding-and-home? margin-less-pages?) "" (util/hiccup->class "mx-auto.pb-24"))
           :style {:margin-bottom  (cond
                                     margin-less-pages? 0
                                     onboarding-and-home? 0
                                     :else 120)}}
          main-content])

       (when onboarding-and-home?
         (onboarding/intro onboarding-and-home?))]]]))

(defonce sidebar-inited? (atom false))
;; TODO: simplify logic

(rum/defc parsing-progress < rum/static
  [state]
  (let [finished (or (:finished state) 0)
        total (:total state)
        width (js/Math.round (* (.toFixed (/ finished total) 2) 100))
        display-filename (some-> (:current-parsing-file state)
                                 not-empty
                                 path/filename)
        left-label [:div.flex.flex-row.font-bold
                    (t :parsing-files)
                    [:div.hidden.md:flex.flex-row
                     [:span.mr-1 ": "]
                     [:div.text-ellipsis-wrapper {:style {:max-width 300}}
                      display-filename]]]]
    (ui/progress-bar-with-label width left-label (str finished "/" total))))

(rum/defc main-content < rum/reactive db-mixins/query
  {:init (fn [state]
           (when-not @sidebar-inited?
             (let [current-repo (state/sub :git/current-repo)
                   default-home (get-default-home-if-valid)
                   sidebar (:sidebar default-home)
                   sidebar (if (string? sidebar) [sidebar] sidebar)]
               (when-let [pages (->> (seq sidebar)
                                     (remove string/blank?))]
                 (doseq [page pages]
                   (let [page (util/safe-page-name-sanity-lc page)
                         [db-id block-type] (if (= page "contents")
                                              ["contents" :contents]
                                              [(:db/id (db/pull [:block/name page])) :page])]
                     (state/sidebar-add-block! current-repo db-id block-type)))
                 (reset! sidebar-inited? true))))
           (when (state/mobile?)
             (state/set-state! :mobile/show-tabbar? true))
           state)}
  []
  (let [default-home (get-default-home-if-valid)
        current-repo (state/sub :git/current-repo)
        loading-files? (when current-repo (state/sub [:repo/loading-files? current-repo]))
        journals-length (state/sub :journals-length)
        latest-journals (db/get-latest-journals (state/get-current-repo) journals-length)
        graph-parsing-state (state/sub [:graph/parsing-state current-repo])]
    (cond
      (or
       (:graph-loading? graph-parsing-state)
       (not= (:total graph-parsing-state) (:finished graph-parsing-state)))
      [:div.flex.items-center.justify-center.full-height-without-header
       [:div.flex-1
        (parsing-progress graph-parsing-state)]]

      :else
      [:div
       (cond
         (and default-home
              (= :home (state/get-current-route))
              (not (state/route-has-p?))
              (:page default-home))
         (route-handler/redirect-to-page! (:page default-home))

         (and config/publishing?
              (not default-home)
              (empty? latest-journals))
         (route-handler/redirect! {:to :all-pages})

         loading-files?
         (ui/loading (t :loading-files))

         (seq latest-journals)
         (journal/journals latest-journals)

         ;; FIXME: why will this happen?
         :else
         [:div])])))

(defn- hide-context-menu-and-clear-selection
  [e]
  (state/hide-custom-context-menu!)
  (let [block (.closest (.-target e) ".ls-block")]
    (when-not (or (gobj/get e "shiftKey")
                  (util/meta-key? e)
                  (state/get-edit-input-id)
                  (and block
                       (or (= block (.-target e))
                           (.contains block (.-target e)))))
      (editor-handler/clear-selection!))))

(rum/defc render-custom-context-menu
  [links position]
  (let [ref (rum/use-ref nil)]
    (rum/use-effect!
     #(let [el (rum/deref ref)
            {:keys [x y]} (util/calc-delta-rect-offset el js/document.documentElement)]
        (set! (.. el -style -transform)
              (str "translate3d(" (if (neg? x) x 0) "px," (if (neg? y) (- y 10) 0) "px" ",0)"))))
    [:<>
     [:div.menu-backdrop {:on-mouse-down (fn [e] (hide-context-menu-and-clear-selection e))}]
     [:div#custom-context-menu
      {:ref ref
       :style {:left (str (first position) "px")
               :top (str (second position) "px")}} links]]))

(rum/defc custom-context-menu < rum/reactive
  []
  (let [show? (state/sub :custom-context-menu/show?)
        links (state/sub :custom-context-menu/links)
        position (state/sub :custom-context-menu/position)]
    (when (and show? links position)
      (render-custom-context-menu links position))))

(rum/defc new-block-mode < rum/reactive
  []
  (when (state/sub [:document/mode?])
    (ui/tippy {:html [:div.p-2
                      [:p.mb-2 [:b (t :document-mode-enabled)]]
                      [:ul
                       [:li
                        [:div.inline-block.mr-1 (ui/render-keyboard-shortcut (shortcut-dh/gen-shortcut-seq :editor/new-line))]
                        [:p.inline-block (t :document-mode/tippy-new-block)]]
                       [:li.text-sm
                        [:p.inline-block.mr-1 (t :document-mode/tippy-toggle1)]
                        [:div.inline-block.mr-1 (ui/render-keyboard-shortcut (shortcut-dh/gen-shortcut-seq :ui/toggle-document-mode))]
                        [:p.inline-block (t :document-mode/tippy-toggle2)]]]]}
              [:a.block.px-1.text-sm.font-medium.bg-base-2.rounded-md.mx-2
               {:on-click state/toggle-document-mode!}
               "D"])))

(def help-menu-items
  [;;{:title (t :help/handbook) :icon "book-2" :on-click #(handbooks/toggle-handbooks)}
   {:title (t :help/shortcuts) :icon "command" :on-click #(state/sidebar-add-block! (state/get-current-repo) "shortcut-settings" :shortcut-settings)}
   {:title (t :help/docs) :icon "help" :href "https://docs.logseq.com/"}
   :hr
  ;;  {:title (t :help/bug) :icon "bug" :on-click #(rfe/push-state :bug-report)}
   ;;{:title t(t :help/feature) :icon "git-pull-request" :href "https://discuss.logseq.com/c/feature-requests/"} Removed from discuss forum
  ;;  {:title (t :help/feedback) :icon "messages" :href "https://discuss.logseq.com/c/feedback/13"}
  ;;  :hr
   {:title (str "Discord " (t :help/title-community)) :icon "brand-discord" :href "https://discord.com/invite/KpN4eHY"}
   {:title (t :help/forum-community) :icon "message" :href "https://discuss.logseq.com/"}
   :hr
   {:title (t :help/changelog) :icon "asterisk" :href "https://github.com/YU000jp/fork-logseq/blob/logseq-classic-develop/CHANGELOG.md"}])

(rum/defc help-menu-popup
  []

  (rum/use-effect!
    (fn []
      (state/set-state! :ui/handbooks-open? false))
    [])

  (rum/use-effect!
    (fn []
      (let [h #(state/set-state! :ui/help-open? false)]
        (.addEventListener js/document.body "click" h)
        #(.removeEventListener js/document.body "click" h)))
    [])

  [:div.cp__sidebar-help-menu-popup
   [:div.list-wrap
    (for [[idx {:keys [title icon href on-click] :as item}] (medley/indexed help-menu-items)]
      (case item
        :hr
        [:hr.my-2 {:key idx}]

        ;; default
        [:a.it.flex.items-center.px-4.py-1.select-none
         {:key      title
          :on-click (fn []
                      (cond
                        (fn? on-click) (on-click)
                        (string? href) (util/open-url href))
                      (state/set-state! :ui/help-open? false))}
         [:span.flex.items-center.pr-2.opacity-40 (ui/icon icon {:size 20})]
         [:strong.font-normal title]]))]
   [:div.ft.pl-11.pb-3
    [:span.opacity.text-xs.opacity-30 "Logseq-Classic " version]]])

(rum/defc help-button < rum/reactive
  []
  (let [help-open?      (state/sub :ui/help-open?)
        handbooks-open? (state/sub :ui/handbooks-open?)]
    [:<>
     [:div.cp__sidebar-help-btn
      [:div.inner
       {:title    (t :help-shortcut-title)
        :on-click #(state/toggle! :ui/help-open?)}
       [:svg.scale-125 {:stroke "currentColor", :fill "none", :stroke-linejoin "round", :width "24", :viewbox "0 0 24 24", :xmlns "http://www.w3.org/2000/svg", :stroke-linecap "round", :stroke-width "2", :class "icon icon-tabler icon-tabler-help-small", :height "24"}
        [:path {:stroke "none", :d "M0 0h24v24H0z", :fill "none"}]
        [:path {:d "M12 16v.01"}]
        [:path {:d "M12 13a2 2 0 0 0 .914 -3.782a1.98 1.98 0 0 0 -2.414 .483"}]]]]

     (when help-open?
       (help-menu-popup))

     (when handbooks-open?
       (handbooks/handbooks-popup))]))

(rum/defcs ^:large-vars/cleanup-todo sidebar <
  (mixins/modal :modal/show?)
  rum/reactive
  (mixins/event-mixin
   (fn [state]
     (mixins/listen state js/window "click" hide-context-menu-and-clear-selection)
     (mixins/listen state js/window "keydown"
                    (fn [e]
                      (when (= 27 (.-keyCode e))
                        (if (and (state/modal-opened?)
                                 (not
                                  (and
                                   ;; FIXME: this does not work on CI tests
                                   util/node-test?
                                   (:editor/editing? @state/state))))
                          (state/close-modal!)
                          (hide-context-menu-and-clear-selection e)))))))
  [state route-match main-content]
  (let [{:keys [open-fn]} state
        current-repo (state/sub :git/current-repo)
        granted? (state/sub [:nfs/user-granted? (state/get-current-repo)])
        theme (state/sub :ui/theme)
        accent-color (some-> (state/sub :ui/radix-color) (name))
        system-theme? (state/sub :ui/system-theme?)
        light? (= "light" (state/sub :ui/theme))
        sidebar-open?  (state/sub :ui/sidebar-open?)
        settings-open? (state/sub :ui/settings-open?)
        left-sidebar-open?  (state/sub :ui/left-sidebar-open?)
        wide-mode? (state/sub :ui/wide-mode?)
        ls-block-hl-colored? (state/sub :pdf/block-highlight-colored?)
        onboarding-state (state/sub :file-sync/onboarding-state)
        right-sidebar-blocks (state/sub-right-sidebar-blocks)
        route-name (get-in route-match [:data :name])
        margin-less-pages? (boolean (#{:graph :whiteboard} route-name))
        db-restoring? (state/sub :db/restoring?)
        indexeddb-support? (state/sub :indexeddb/support?)
        page? (= :page route-name)
        home? (= :home route-name)
        native-titlebar? (state/sub [:electron/user-cfgs :window/native-titlebar?])
        window-controls? (and (util/electron?) (not util/mac?) (not native-titlebar?))
        edit? (:editor/editing? @state/state)
        default-home (get-default-home-if-valid)
        logged? (user-handler/logged-in?)
        fold-button-on-right? (state/enable-fold-button-right?)
        show-action-bar? (state/sub :mobile/show-action-bar?)
        show-recording-bar? (state/sub :mobile/show-recording-bar?)
        preferred-language (state/sub [:preferred-language])]
    (theme/container
     {:t             t
      :theme         theme
      :accent-color  accent-color
      :route         route-match
      :current-repo  current-repo
      :edit?         edit?
      :nfs-granted?  granted?
      :db-restoring? db-restoring?
      :sidebar-open? sidebar-open?
      :settings-open? settings-open?
      :sidebar-blocks-len (count right-sidebar-blocks)
      :system-theme? system-theme?
      :onboarding-state onboarding-state
      :preferred-language preferred-language
      :on-click      (fn [e]
                       (editor-handler/unhighlight-blocks!)
                       (util/fix-open-external-with-shift! e))}

     [:main.theme-inner
      {:class (util/classnames [{:ls-left-sidebar-open    left-sidebar-open?
                                 :ls-right-sidebar-open   sidebar-open?
                                 :ls-wide-mode            wide-mode?
                                 :ls-window-controls      window-controls?
                                 :ls-fold-button-on-right fold-button-on-right?
                                 :ls-hl-colored           ls-block-hl-colored?}])}

      [:button#skip-to-main
       {:on-click #(ui/focus-element (ui/main-node))
        :on-key-up (fn [e]
                     (when (= (.-key e) "Enter")
                       (ui/focus-element (ui/main-node))))}
       (t :accessibility/skip-to-main-content)]
      [:div.#app-container
       [:div#left-container
        {:class (if (state/sub :ui/sidebar-open?) "overflow-hidden" "w-full")}
        (header/header {:open-fn        open-fn
                        :light?         light?
                        :current-repo   current-repo
                        :logged?        logged?
                        :page?          page?
                        :route-match    route-match
                        :default-home   default-home
                        :new-block-mode new-block-mode})
        (when (util/electron?)
          (find-in-page/search))

        (main {:route-match         route-match
               :margin-less-pages?  margin-less-pages?
               :logged?             logged?
               :home?               home?
               :route-name          route-name
               :indexeddb-support?  indexeddb-support?
               :light?              light?
               :db-restoring?       db-restoring?
               :main-content        main-content
               :show-action-bar?    show-action-bar?
               :show-recording-bar? show-recording-bar?})]

       (when window-controls?
         (window-controls/container))

       (right-sidebar/sidebar)

       [:div#app-single-container]]

      (ui/notification)
      (ui/modal)
      (ui/sub-modal)
      (shui-toaster/install-toaster)
      (shui-dialog/install-modals)
      (select/select-modal)
      (custom-context-menu)
      (plugins/custom-js-installer {:t t
                                    :current-repo current-repo
                                    :nfs-granted? granted?
                                    :db-restoring? db-restoring?})
      [:a#download.hidden]
      (when (and (not config/mobile?)
                 (not config/publishing?))
        (help-button))])))
