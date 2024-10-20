(ns frontend.components.right-sidebar
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [frontend.components.block :as block]
            ;;[frontend.components.onboarding :as onboarding]
            [frontend.components.scheduled-deadlines :as scheduled]
            [frontend.components.page :as page]
            [frontend.components.shortcut-help :as shortcut-help]
            [frontend.components.cmdk :as cmdk]
            [frontend.context.i18n :refer [t]]
            [frontend.date :as date]
            [frontend.db :as db]
            [frontend.db-mixins :as db-mixins]
            [frontend.db.model :as db-model]
            [frontend.extensions.slide :as slide]
            [frontend.handler.editor :as editor-handler]
            [frontend.components.file :as file]
            ;; [frontend.handler.user :as user-handler]
            [frontend.handler.route :as route-handler]
            [frontend.components.reference :as reference]
            [frontend.handler.ui :as ui-handler]
            ;; [frontend.handler.page :as page-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [logseq.shui.ui :as shui]
            [frontend.util :as util]
            [frontend.config :as config]
            [frontend.modules.editor.undo-redo :as undo-redo]
            [medley.core :as medley]
            [reitit.frontend.easy :as rfe]
            [rum.core :as rum]))

(rum/defc toggle
  []
  (when-not (util/sm-breakpoint?)
    (ui/with-shortcut :ui/toggle-right-sidebar "left"
      [:button.button.icon.toggle-right-sidebar.ml-4
       {:style {:cursor "col-resize"}
        :title (t :right-side-bar/toggle-right-sidebar)
        :on-click ui-handler/toggle-right-sidebar!}
       (ui/icon
        "layout-sidebar-right"
        {:size 26
         :color (if (:ui/sidebar-open? @state/state) "green" "gray")})])))

(rum/defc block-cp < rum/reactive
  [repo idx block]
  (let [id (:block/uuid block)]
    (page/page {:parameters  {:path {:name (str id)}}
                :sidebar?    true
                :sidebar/idx idx
                :repo        repo})))

(rum/defc page-cp < rum/reactive
  [repo page-name]
  (page/page {:parameters {:path {:name page-name}}
              :sidebar?   true
              :repo       repo}))

(rum/defc contents < rum/reactive db-mixins/query
  []
  [:div.contents.flex-col.flex.ml-3
   (when-let [contents (db/entity [:block/name "contents"])]
     (page/contents-page contents))])

(rum/defc shortcut-settings
  []
  [:div.contents.flex-col.flex.ml-3
   (shortcut-help/shortcut-page {:show-title? false})])

(rum/defc syntax-help
  []
  [:div.contents.flex-col.flex.ml-3
   (shortcut-help/syntax-page {:show-title? false})])

(defn- block-with-breadcrumb
  [repo block idx sidebar-key ref?]
  (when-let [block-id (:block/uuid block)]
    [[:.flex.items-center
      [:span.text-sm.mr-4 (if ref? (t :right-side-bar/block-ref)
                              (t :right-side-bar/opened-block))]
      (block/breadcrumb {:id     "block-parent"
                         :block? true
                         :sidebar-key sidebar-key} repo block-id {:indent? false})]
     (block-cp repo idx block)]))

(rum/defc history-action-info
  [[k v]]
  (when v [:.ml-4 (ui/foldable
                   [:div (str k)]
                   [:.ml-4 (case k
                             :tx-id
                             [:.my-1 [:pre.code.pre-wrap-white-space.bg-base-4 (str v)]]

                             :blocks
                             (map (fn [block]
                                    [:.my-1 [:pre.code.pre-wrap-white-space.bg-base-4 (str block)]]) v)

                             :txs
                             (map (fn [[_ key val]]
                                    (when val
                                      [:pre.code.pre-wrap-white-space.bg-base-4
                                       [:span.font-bold (str key) " "] (str val)])) v)

                             (map (fn [[key val]]
                                    (when val
                                      [:pre.code.pre-wrap-white-space.bg-base-4
                                       [:span.font-bold (str key) " "] (str val)])) v))]
                   {:default-collapsed? true})]))

(rum/defc history-stack
  [label stack]
  [:.ml-4 (ui/foldable
           [:div label " (" (count stack) ")"]
           (map-indexed (fn [index item]
                          [:.ml-4 (ui/foldable [:div (str index " " (-> item :tx-meta :outliner-op))]
                                               (map history-action-info item)
                                               {:default-collapsed? true})]) stack)
           {:default-collapsed? true})])

(rum/defc history < rum/reactive
  []
  (let [state (undo-redo/get-state)
        page-only-mode? (state/sub :history/page-only-mode?)]
    [:div.ml-4
     [:div.ml-3.font-bold (if page-only-mode?
                            (t :right-side-bar/history-pageonly)
                            (t :right-side-bar/history-global))]
     [:div.p-4
      [:.ml-4.mb-2
       (history-stack (t :right-side-bar/history-undos) (rum/react (:undo-stack state)))
       (history-stack (t :right-side-bar/history-redos) (rum/react (:redo-stack state)))]]]))


(rum/defc headers-list < rum/reactive db-mixins/query
  [repo current-page-name-or-uuid]

  (let [zoom-in (parse-uuid current-page-name-or-uuid)
        current-page-name (if zoom-in
                            (get-in (db-model/get-block-by-uuid current-page-name-or-uuid) [:block/page :block/name])
                            current-page-name-or-uuid)]
    (when-let [entity (db/entity [:block/name current-page-name])]
      [(when zoom-in
         [[:div
           (get-in entity [:block/original-name])]
          [:style
           (str "
.item-type-headers-list .ls-block {
&[blockid='" current-page-name-or-uuid "'] {
background: var(--ls-selection-background-color);
opacity: 1;
}
&:not([blockid='" current-page-name-or-uuid "']) {
opacity: 0.6;
}
}
                                            ")]])
       [:div.flex-col.flex.ml-3
        (page/page-blocks-cp repo entity {:sidebar? true
                                          :headerList? true})]])))


(defn build-sidebar-item
  [repo idx db-id block-type *db-id init-key]
  ;; (let  [current-page-name-or-uuid (or (state/get-current-page) (state/get-current-whiteboard))]
  (case (keyword block-type)
    :contents
    [[:.flex.items-center
      (ui/icon "note" {:class "text-sm mr-2"})
      (t :right-side-bar/contents)]
     (contents)]

    ;; :help
    ;; [[:.flex.items-center (ui/icon "help" {:class "text-md mr-2"}) (t :right-side-bar/help)] (onboarding/help)]

    :scheduled-and-deadline
    [[:.flex.items-center#open-sidebar-scheduled-and-deadline
      (ui/icon "calendar-time" {:class "text-sm mr-1"})
      [:span.overflow-hidden.text-ellipsis (t :right-side-bar/scheduled-and-deadline)]]
     (scheduled/scheduled-and-deadlines (date/today))]

    :repeat-tasks
    [[:.flex.items-center#open-sidebar-repeat-tasks
      (ui/icon "repeat" {:class "text-sm mr-1"})
      [:span.overflow-hidden.text-ellipsis (t :right-side-bar/repeat-tasks)]]
     (scheduled/repeat-tasks (date/today))]

      ;; :default-queries
      ;; [[:.flex.items-center#open-sidebar-default-queries
      ;;   (ui/icon "brand-4chan" {:class "mr-2"})
      ;;   [:span.overflow-hidden.text-ellipsis (t :right-side-bar/default-queries)]]
      ;;  (page/today-queries repo)]

    :reference
    [[:.flex.items-center#open-sidebar-reference
      (ui/icon "layers-difference" {:class "mr-2"})
      [:span.overflow-hidden.text-ellipsis
       [(t :linked-references/sidebar-open) " >> " db-id]]]
     (if-let [page-name db-id]
       [[:div {:key "page-references"}
         (reference/references page-name)]
        [:div.text-sm.opacity-50.ml-4.mt-6#long-time-message (t :right-side-bar/long-time)]]
       (t :linked-references/sidebar-not-page))]

      ;; :unlinked-reference
      ;; [[:.flex.items-center#open-sidebar-reference
      ;;   (ui/icon "list" {:class "mr-2"})
      ;;   [:span.overflow-hidden.text-ellipsis
      ;;    (t :unlinked-references/sidebar-open) " >> " db-id]]
      ;;  (if-let [page-name db-id]
      ;;    [[:div {:key "page-unlinked-references"}
      ;;      (reference/unlinked-references page-name)]
      ;;     [:div.text-sm.opacity-50.ml-4.mt-6#long-time-message (t :right-side-bar/long-time)]]
      ;;    (t :unlinked-references/sidebar-not-page))]

    :page-graph
    [[:.flex.items-center
      [(ui/icon "hierarchy" {:class "mr-2"})
       (t :right-side-bar/page-graph)]
      [:span.text-sm.opacity-50.ml-4 (t :right-side-bar/long-time)]]
     (page/page-graph)]

      ;; :headers-list
      ;; [[:.flex.items-center
      ;;   (shui/tabler-icon "pennant" {:class "mr-2"})
      ;;   [:span.overflow-hidden.text-ellipsis
      ;;    (t :right-side-bar/page-headers-list)]]
      ;;  (if current-page-name-or-uuid
      ;;    (headers-list repo current-page-name-or-uuid)
      ;;    [:div
      ;;     "No headers"])]

    :history
    [[:.flex.items-center
      (ui/icon "history" {:class "mr-2"})
      (t :right-side-bar/history)]
     (history)]

    :all-pages
    [[:.flex.items-center
      (ui/icon "book" {:class "mr-2"})
      (t :right-side-bar/all-pages)]
     (page/all-pages)]

    :all-files
    [[:.flex.items-center
      (ui/icon "files" {:class "mr-2"})
      (t :right-side-bar/all-files)]
     (file/files)]

    :block-ref
    #_:clj-kondo/ignore
    (let [lookup (if (integer? db-id) db-id [:block/uuid db-id])]
      (when-let [block (db/entity repo lookup)]
        (block-with-breadcrumb repo block idx [repo db-id block-type] true)))

    :block
    #_:clj-kondo/ignore
    (let [lookup (if (integer? db-id) db-id [:block/uuid db-id])]
      (when-let [block (db/entity repo lookup)]
        (block-with-breadcrumb repo block idx [repo db-id block-type] false)))

    :page
    (let [lookup (if (integer? db-id) db-id [:block/uuid db-id])
          page (db/entity repo lookup)
          page-name (:block/name page)]
      [[:.flex.items-center.page-title
        [:span.text-sm.mr-4 (t :right-side-bar/opened-page)]
        (if-let [icon (get-in page [:block/properties :icon])]
          [icon]
          (ui/icon (if (= "whiteboard" (:block/type page)) "whiteboard" "page") {:class "mr-2"}))
        [:span.overflow-hidden.text-ellipsis (db-model/get-page-original-name page-name)]]
       (page-cp repo page-name)])

    :search
    [[:.flex.items-center
      (ui/icon "search" {:class "mr-1"})
      (let [input (rum/react *db-id)
            input' (if (string/blank? input) (t :search/what-are-you-looking-for) input)]
        [:span.overflow-hidden.text-ellipsis input'])]
     (rum/with-key
       (cmdk/cmdk-block {:initial-input db-id
                         :sidebar? true
                         :on-input-change (fn [new-value]
                                            (reset! *db-id new-value))
                         :on-input-blur (fn [new-value]
                                          (state/sidebar-replace-block! [repo db-id block-type]
                                                                        [repo new-value block-type]))})
       (str init-key))]

    :page-slide-view
    (let [page-name (:block/name (db/entity db-id))]
      [[:a.page-title {:href (rfe/href :page {:name page-name})}
        (db-model/get-page-original-name page-name)]
       [:div.ml-2.slide.mt-2
        (slide/slide page-name)]])

    :shortcut-settings
    [[:.flex.items-center (ui/icon "command" {:class "text-md mr-2"}) (t :help/shortcuts)]
     (shortcut-settings)]

    :syntax-help
    [[:.flex.items-center (ui/icon "vector-bezier" {:class "text-md mr-2"}) (t :right-side-bar/syntax)]
     (syntax-help)]

    ["" [:span]]))
      ;; )

(defonce *drag-to
  (atom nil))

(defonce *drag-from
  (atom nil))

(rum/defc x-menu-content
  [db-id idx type collapsed? block-count toggle-fn as-dropdown?]
  (let [menu-content (if as-dropdown? shui/dropdown-menu-content shui/context-menu-content)
        menu-item (if as-dropdown? shui/dropdown-menu-item shui/context-menu-content)
        multi-items? (> block-count 1)]

    (menu-content
     {:on-click toggle-fn
      :class "w-48"
      :align "end"}
     (when multi-items?
       (menu-item
        {:on-click #(state/sidebar-remove-rest! db-id)}
        (t :right-side-bar/pane-close-others)))

     (when multi-items?
       (menu-item
        {:on-click #(state/sidebar-block-collapse-rest! db-id)}
        (t :right-side-bar/pane-collapse-others)))
     (when-not multi-items?
       (when-not collapsed?
         (menu-item
          {:on-click #(state/sidebar-block-toggle-collapse! db-id)}
          (t :right-side-bar/pane-collapse)))
       (when collapsed?
         (menu-item
          {:on-click #(state/sidebar-block-toggle-collapse! db-id)}
          (t :right-side-bar/pane-expand))))
     (when (= type :page)
       (let [page-name (:block/name (db/entity db-id))]
         (menu-item
          {:on-click (fn []
                       (if (db-model/whiteboard-page? page-name)
                         (route-handler/redirect-to-whiteboard! page-name)
                         (route-handler/redirect-to-page! page-name)))}
          (t :right-side-bar/pane-open-as-page)))))))


(rum/defc drop-indicator
  [idx drag-to]
  [:.sidebar-drop-indicator {:on-drag-enter #(when drag-to (reset! *drag-to idx))
                             :on-drag-over util/stop
                             :class (when (= idx drag-to) "drag-over")}])

(rum/defc drop-area
  [idx]
  [:.sidebar-item-drop-area
   {:on-drag-over util/stop}
   [:.sidebar-item-drop-area-overlay.top
    {:on-drag-enter #(reset! *drag-to (dec idx))}]
   [:.sidebar-item-drop-area-overlay.bottom
    {:on-drag-enter #(reset! *drag-to idx)}]])

(rum/defc inner-component <
  {:should-update (fn [_prev-state state] (last (:rum/args state)))}
  [component _should-update?]
  component)

(rum/defcs sidebar-item < rum/reactive
  {:init (fn [state] (assoc state
                            ::db-id (atom (nth (:rum/args state) 2))
                            ::init-key (random-uuid)))}
  [state repo idx db-id block-type block-count]
  (let [drag-from (rum/react *drag-from)
        drag-to (rum/react *drag-to)
        item (build-sidebar-item repo idx db-id block-type
                                 (::db-id state)
                                 (::init-key state))]
    (when item
      (let [collapsed? (state/sub [:ui/sidebar-collapsed-blocks db-id])]
        [:<>
         (when (zero? idx) (drop-indicator (dec idx) drag-to))
         [:div.flex.sidebar-item.content.color-level.rounded-md.shadow-lg
          {:class [(str "item-type-" (name block-type))
                   (when collapsed? "collapsed")]}
          (let [[title component] item]
            [:div.flex.flex-col.w-full.relative
             [:.flex.flex-row.justify-between.pr-2.sidebar-item-header.color-level.rounded-t-md
              {:class         (when collapsed? "rounded-b-md")
               :draggable     true
               :on-drag-start (fn [event]
                                (editor-handler/block->data-transfer! (:block/name (db/entity db-id)) event)
                                (reset! *drag-from idx))
               :on-drag-end   (fn [_event]
                                (when drag-to (state/sidebar-move-block! idx drag-to))
                                (reset! *drag-to nil)
                                (reset! *drag-from nil))
               :on-mouse-up   (fn [event]
                                (when (= (.-which (.-nativeEvent event)) 2)
                                  (state/sidebar-remove-block! idx)))}

              [:button.flex.flex-row.p-2.w-full.overflow-hidden
               {:style {:cursor "grab"}
                :aria-expanded (str (not collapsed?))
                :id            (str "sidebar-panel-header-" idx)
                :aria-controls (str "sidebar-panel-content-" idx)
                :on-click      (fn [event]
                                 (util/stop event)
                                 (if (and (util/shift-key? event) (not collapsed?))
                                   (state/sidebar-block-collapse-rest! db-id)
                                   (state/sidebar-block-toggle-collapse! db-id)))}
               [:span.opacity-50.hover:opacity-100.flex.items-center.pr-1
                (ui/rotating-arrow collapsed?)]
               [:div.ml-1.font-medium.overflow-hidden.whitespace-nowrap
                title]]
              [:.item-actions.flex
               (shui/dropdown-menu
                (shui/dropdown-menu-trigger
                 {:as-child true}
                 (shui/button
                  {:title   (t :right-side-bar/pane-more)
                   :class   "px-3"
                   :variant :text}
                  (ui/icon "dots")))
                (x-menu-content db-id idx block-type collapsed? block-count #() true))

               (shui/button
                {:title    (t :right-side-bar/pane-close)
                 :variant  :text
                 :class "px-3"
                 :on-click #(state/sidebar-remove-block! idx)}
                (ui/icon "x" {:color "red"}))]]

             [:div {:role            "region"
                    :id              (str "sidebar-panel-content-" idx)
                    :aria-labelledby (str "sidebar-panel-header-" idx)
                    :class           (util/classnames [{:hidden  collapsed?
                                                        :initial (not collapsed?)
                                                        :p-4     (not (contains? #{:page :block :contents :search :shortcut-settings :syntax-help} block-type))
                                                        :pt-4    (not (contains? #{:search :shortcut-settings :syntax-help} block-type))
                                                        :p-1     (not (contains? #{:search :shortcut-settings :syntax-help} block-type))}])}
              (inner-component component (not drag-from))]
             (when drag-from (drop-area idx))])]
         (drop-indicator idx drag-to)]))))


(rum/defc sidebar-resizer
  [sidebar-open? sidebar-id handler-position]
  (let [el-ref (rum/use-ref nil)
        min-px-width 320 ; Custom window controls width
        min-ratio 0.1
        max-ratio 0.7
        keyboard-step 5
        add-resizing-class #(.. js/document.documentElement -classList (add "is-resizing-buf"))
        remove-resizing-class (fn []
                                (.. js/document.documentElement -classList (remove "is-resizing-buf"))
                                (reset! ui-handler/*right-sidebar-resized-at (js/Date.now)))
        set-width! (fn [ratio]
                     (when el-ref
                       (let [value (* ratio 100)
                             width (str value "%")]
                         (.setAttribute (rum/deref el-ref) "aria-valuenow" value)
                         (ui-handler/persist-right-sidebar-width! width))))]
    (rum/use-effect!
     (fn []
       (when-let [el (and (fn? js/window.interact) (rum/deref el-ref))]
         (-> (js/interact el)
             (.draggable
              (bean/->js
               {:listeners
                {:move
                 (fn [^js/MouseEvent e]
                   (let [width js/document.documentElement.clientWidth
                         min-ratio (max min-ratio (/ min-px-width width))
                         sidebar-el (js/document.getElementById sidebar-id)
                         offset (.-pageX e)
                         ratio (.toFixed (/ offset width) 6)
                         ratio (if (= handler-position :west) (- 1 ratio) ratio)
                         cursor-class (str "cursor-" (first (name handler-position)) "-resize")]
                     (if (= (.getAttribute el "data-expanded") "true")
                       (cond
                         (< ratio (/ min-ratio 2))
                         (state/hide-right-sidebar!)

                         (< ratio min-ratio)
                         (.. js/document.documentElement -classList (add cursor-class))

                         (and (< ratio max-ratio) sidebar-el)
                         (when sidebar-el
                           (#(.. js/document.documentElement -classList (remove cursor-class))
                            (set-width! ratio)))
                         :else
                         #(.. js/document.documentElement -classList (remove cursor-class)))
                       (when (> ratio (/ min-ratio 2)) (state/open-right-sidebar!)))))}}))
             (.styleCursor false)
             (.on "dragstart" add-resizing-class)
             (.on "dragend" remove-resizing-class)
             (.on "keydown" (fn [e]
                              (when-let [sidebar-el (js/document.getElementById sidebar-id)]
                                (let [width js/document.documentElement.clientWidth
                                      min-ratio (max min-ratio (/ min-px-width width))
                                      keyboard-step (case (.-code e)
                                                      "ArrowLeft" (- keyboard-step)
                                                      "ArrowRight" keyboard-step
                                                      0)
                                      offset (+ (.-x (.getBoundingClientRect sidebar-el)) keyboard-step)
                                      ratio (.toFixed (/ offset width) 6)
                                      ratio (if (= handler-position :west) (- 1 ratio) ratio)]
                                  (when (and (> ratio min-ratio) (< ratio max-ratio) (not (zero? keyboard-step)))
                                    (do (add-resizing-class)
                                        (set-width! ratio)))))))
             (.on "keyup" remove-resizing-class)))
       #())
     [])

    (rum/use-effect!
     (fn []
        ;; sidebar animation duration
       (js/setTimeout
        #(reset! ui-handler/*right-sidebar-resized-at (js/Date.now)) 300))
     [sidebar-open?])

    [:.resizer
     {:ref              el-ref
      :role             "separator"
      :aria-orientation "vertical"
      :aria-label       (t :right-side-bar/separator)
      :aria-valuemin    (* min-ratio 100)
      :aria-valuemax    (* max-ratio 100)
      :aria-valuenow    50
      :tabIndex         "0"
      :data-expanded    sidebar-open?}]))

(rum/defcs sidebar-inner <
  (rum/local false ::anim-finished?)
  {:will-mount (fn [state]
                 (js/setTimeout (fn [] (reset! (get state ::anim-finished?) true)) 300)
                 state)}
  [state repo t blocks]
  (let [*anim-finished? (get state ::anim-finished?)
        block-count (count blocks)
        repo (state/get-current-repo)
        demo? (config/demo-graph? repo)]
    [:div.cp__right-sidebar-inner.flex.flex-col.h-full#right-sidebar-container

     [:div.cp__right-sidebar-scrollable
      {:on-drag-over util/stop}
      [:div.cp__right-sidebar-topbar.flex.flex-row.justify-between.items-center
       [:div.cp__right-sidebar-settings.hide-scrollbar.gap-1 {:key "right-sidebar-settings"}

        ;; Search
        [:div.text-sm
         [:button.button.cp__right-sidebar-settings-btn {:on-click (fn [_e]
                                                                      ;; サイドバーで検索を開く 
                                                                     [(state/close-modal!)
                                                                      (state/sidebar-add-block! repo "" :search)])
                                                         :title (t :header/search)}
          [(ui/icon "search" {:class "icon" :size 23 :color "gray"})
           [:span.ml-1.mr-2
            (t :header/search)]]]]

        ;; Content
        [:div.text-sm
         [:button.button.cp__right-sidebar-settings-btn {:on-click (fn [_e]
                                                                     (state/sidebar-add-block! repo "" :contents))
                                                         :title (t :right-side-bar/contents)}
          [(ui/icon "note" {:class "icon" :size 23 :color "gray"})
           [:span.ml-1.mr-2
            (t :right-side-bar/contents)]]]]


        ;; Table of Contents (Headers List)
        ;; [:div.text-sm
        ;;  [:button.button.cp__right-sidebar-settings-btn {:on-click (fn [_e]
        ;;                                                              (state/sidebar-add-block! repo "headers-list" :headers-list))
        ;;                                                  :title (t :right-side-bar/page-headers-list)}
        ;;   [(ui/icon "pennant" {:class "icon" :size 23 :color "gray"})
        ;;    [:span.ml-1.mr-2
        ;;     (t :right-side-bar/page-headers-list)]]]]

        ;; dafault-queries 
        ;; (when (and repo
        ;;            (not demo?))
        ;;   [:div.text-sm
        ;;    [:button.button.cp__right-sidebar-settings-btn {:style {:cursor "alias"}
        ;;                                                    :on-click (fn [_e]
        ;;                                                                (state/sidebar-add-block! repo "default-queries" :default-queries))
        ;;                                                    :title (t :right-side-bar/default-queries)}
        ;;     (ui/icon "brand-4chan" {:class "icon" :size 23 :color "gray"})]])


        ;; 今日のジャーナルを開く
        ;; [:div.text-sm
        ;;  [:button.button.cp__right-sidebar-settings-btn {:style {:cursor "alias"}
        ;;                                                  :on-click (fn [_e]
        ;;                                                              (page-handler/open-today-in-sidebar))
        ;;                                                  :title (t :command.go/today)}
        ;;   (ui/icon "clock" {:class "icon" :size 23 :color "gray"})]]

        ;; ;; すべてのページ を開く
        ;; [:div.text-sm
        ;;  [:button.button.cp__right-sidebar-settings-btn {:style {:cursor "alias"}
        ;;                                                  :on-click (fn [_e]
        ;;                                                              (state/sidebar-add-block! repo "all-pages" :all-pages))
        ;;                                                  :title (t :right-side-bar/all-pages)}
        ;;   (ui/icon "book" {:class "icon" :size 23 :color "gray"})]]

        ;; ;; すべてのファイル を開く
        ;; [:div.text-sm
        ;;  [:button.button.cp__right-sidebar-settings-btn {:style {:cursor "alias"}
        ;;                                                  :on-click (fn [_e]
        ;;                                                              (state/sidebar-add-block! repo "all-files" :all-files))
        ;;                                                  :title (t :right-side-bar/all-files)}
        ;;   (ui/icon "files" {:class "icon" :size 23 :color "gray"})]]

        ;; キーボードショートカットへ移動
        ;; [:div.text-sm
        ;;  [:button.button.cp__right-sidebar-settings-btn {:style {:cursor "help"}
        ;;                                                  :on-click (fn [_e]
        ;;                                                              (state/sidebar-add-block! repo "shortcut-settings" :shortcut-settings))
        ;;                                                  :title (t :command.go/keyboard-shortcuts)}
        ;;   [(ui/icon "keyboard" {:class "icon" :color "gray"})
        ;;    [:span.ml-1.mr-2
        ;;     (t :command.go/keyboard-shortcuts)]]]]

        ;; Syntaxへ移動
        ;; [:div.text-sm
        ;;  [:button.button.cp__right-sidebar-settings-btn {:style {:cursor "help"}
        ;;                                                  :on-click (fn [_e]
        ;;                                                              (state/sidebar-add-block! repo ":syntax-help" :syntax-help))
        ;;                                                  :title (t :right-side-bar/syntax)}
        ;;   [(ui/icon "vector-bezier" {:class "icon" :color "gray"})
        ;;    [:span.ml-1.mr-2
        ;;     (t :right-side-bar/syntax)]]]]

        ;; すべて折りたたむ
        [:div.text-sm
         [:button.button.cp__right-sidebar-settings-btn {:style {:cursor "zoom-out"}
                                                         :on-click (fn [_e]
                                                                     (state/sidebar-block-set-collapsed-all! true))
                                                         :title (t  :right-side-bar/pane-collapse-all)}
          [(ui/icon "box-multiple-0" {:class "icon" :size 23 :color "gray"})
           [:span.ml-1.mr-2
            (t  :right-side-bar/pane-collapse-all)]]]]

        ;; すべて展開する
        [:div.text-sm
         [:button.button.cp__right-sidebar-settings-btn {:style {:cursor "zoom-in"}
                                                         :on-click (fn [_e]
                                                                     (state/sidebar-block-set-collapsed-all! false))
                                                         :title (t :right-side-bar/pane-expand-all)}
          [(ui/icon "box-multiple" {:class "icon" :size 23 :color "gray"})
           [:span.ml-1.mr-2
            (t :right-side-bar/pane-expand-all)]]]]

                ;; サイドバーをクリアにする
        [:div.text-sm
         [:button.button.cp__right-sidebar-settings-btn {:on-click (fn [_e]
                                                                     (state/clear-sidebar-blocks!))
                                                         :title (t :command.sidebar/clear)}
          [(ui/icon "circle-minus" {:class "icon" :color "red"})
           [:span.ml-1.mr-2
            (t :command.sidebar/clear)]]]]

        ;; [:div.text-sm
        ;;  [:button.button.cp__right-sidebar-settings-btn {:on-click (fn [_e]
        ;;                                                              (state/sidebar-add-block! repo "help" :help))}
        ;;   (t :right-side-bar/help)]]

        (when (and config/dev?
                   (state/sub [:ui/developer-mode?]))
          [:div.text-sm
           [:button.button.cp__right-sidebar-settings-btn {:on-click (fn [_e]
                                                                       (state/sidebar-add-block! repo "history" :history))
                                                           :title (t :right-side-bar/history)}

            [(ui/icon "history" {:class "icon" :size 18 :color "gray"})
             [:span.ml-1.mr-2
              (t :right-side-bar/history)]]]])]]

      [:.sidebar-item-list.flex-1.scrollbar-spacing.px-2
       (if @*anim-finished?
         (for [[idx [repo db-id block-type]] (medley/indexed blocks)]
           (rum/with-key
             (sidebar-item repo idx db-id block-type block-count)
             (str "sidebar-block-" db-id)))
         [:div.p-4
          [:span.font-medium.opacity-50 "Loading ..."]])]]]))

(rum/defcs sidebar < rum/reactive
  [state]
  (let [blocks (state/sub-right-sidebar-blocks)
        blocks (if (empty? blocks)
                 [[(state/get-current-repo) "contents" :contents nil]]
                 blocks)
        sidebar-open? (state/sub :ui/sidebar-open?)
        width (state/sub :ui/sidebar-width)
        repo (state/sub :git/current-repo)]
    [:div#right-sidebar.cp__right-sidebar.h-screen
     {:class (if sidebar-open? "open" "closed")
      :style {:width width}}
     (sidebar-resizer sidebar-open? "right-sidebar" :west)
     (when sidebar-open?
       (sidebar-inner repo t blocks))]))
