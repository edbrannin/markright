(ns markright.main
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require [cljs.core.async :refer [<!]]
            [cljs.nodejs :as nodejs]
            [electron.ipc :as ipc]
            [cljs.core.async :as async]
            [clojure.string :refer [split]]
            [markright.dialogs :refer [unsaved-changes-dialog save-dialog open-dialog update-dialog error-dialog]])
  (:import [goog.net XhrIo]))

(def *win* (atom nil))
(def backend-state (atom {:frontend-loaded false :content nil :filepath nil}))

(def https (nodejs/require "https"))
(def path (nodejs/require "path"))
(def BrowserWindow (nodejs/require "browser-window"))
(def crash-reporter (nodejs/require "crash-reporter"))

(def app (nodejs/require "app"))
(def shell (nodejs/require "shell"))
(def fs (nodejs/require "fs"))
(def process (nodejs/require "process"))

(def menu (nodejs/require "menu"))

(def app-name "MarkRight")

;; events from frontend
(defmethod ipc/process-cast :init-frontend
  [_]
  (swap! backend-state assoc
         :frontend-loaded true))

(defmethod ipc/process-call :backend-state
  [msg reply]
  (let [state @backend-state]
    (reply state)))

;; Functions
(defn reload! []
  (.reload @*win*))

(defn toggle-devtools! []
  (.toggleDevTools @*win*))

(defn open-url! [url]
  (.openExternal shell url))

(defn write-file! [filepath content]
  (try
    (.writeFileSync fs filepath content #js {:encoding "utf8"})
    (catch js/Error e
      (error-dialog @*win* "Error while saving document" (str e)))))

(defn verify-unsaved-changes []
  (go
    ;; if we don't have a window or no frontend, there is no need to ask for changes
    ;; even if we wanted to, this wouldn't work because the frontend can't tell us if there are changes
    (if (or (nil? @*win*) (not (@backend-state :frontend-loaded)))
      true
      (let [is-saved? (<! (ipc/call :get-is-saved {}))
            confirm (if is-saved? 0 (unsaved-changes-dialog @*win*))]
        (if (= confirm 0) true false)))))

(defn load-file! [filepath]
  (let [content (.readFileSync fs filepath #js {:encoding "utf8"})]
    (swap! backend-state assoc
           :content content
           :filepath filepath)

    (if (@backend-state :frontend-loaded)
      (ipc/cast :load-file {:file filepath
                            :content (.readFileSync fs filepath #js {:encoding "utf8"})}))))

(defn open-file! []
  (go (if (<! (verify-unsaved-changes))
        (let [file (first (open-dialog @*win*))]
          (if (not (nil? file))
            (load-file! file))))))

(defn quit-app! []
  (go (if (<! (verify-unsaved-changes))
        (.exit app))))

(defn save-html-as! []
  (go (let [file-path (save-dialog @*win*)
            content (<! (ipc/call :get-current-content {}))]
         (if (not (nil? file-path))
           (do
             (write-file! file-path (js/marked content))
             (ipc/cast :set-current-file {:file file-path
                                          :content content}))))))

(defn copy-as-html! []
  (go (let [file-path (save-dialog @*win*)
            content (<! (ipc/call :get-current-content {}))]
         (if (not (nil? file-path))
           (do
             (write-file! file-path (js/marked content))
             (ipc/cast :set-current-file {:file file-path
                                          :content content}))))))

(defn copy-as-rich-text! []
  (go (let [file-path (save-dialog @*win*)
            content (<! (ipc/call :get-current-content {}))]
         (if (not (nil? file-path))
           (do
             (write-file! file-path content)
             (ipc/cast :set-current-file {:file file-path
                                          :content content}))))))

(defn save-file-as! []
  (go (let [file-path (save-dialog @*win*)
            content (<! (ipc/call :get-current-content {}))]
         (if (not (nil? file-path))
           (do
             (write-file! file-path content)
             (ipc/cast :set-current-file {:file file-path
                                          :content content}))))))

(defn save-file! []
  (go (let [content (<! (ipc/call :get-current-content {}))
            filepath (<! (ipc/call :get-current-file {}))]
        (.-length filepath)
        (if (= 0 (.-length filepath))
          (save-file-as!)
          (do
            (write-file! filepath content)
            (ipc/cast :set-saved-content {:content content}))))))

;; Menu structure
(def markright
  {:label "MarkRight"
   :submenu
   [{:label (str "About " app-name)
     :role "about"}

    {:type "separator"}

    {:label "Services"
     :role "services"
     :submenu []}

    {:type "separator"}

    {:label (str "Hide " app-name)
     :accelerator "CmdOrCtrl+H"
     :role "hide"}

    {:label "Hide Others"
     :accelerator "CmdOrCtrl+Shift+H"
     :role "hideothers"}

    {:label "Show All"
     :role "unhide"}

    {:type "separator"}

    {:label "Quit"
     :accelerator "CmdOrCtrl+Q"
     :click quit-app!}]})


(def file
  {:label "File"
   :submenu
   [{:label "Open..."
     :accelerator "CmdOrCtrl+O"
     :click open-file!}

    {:label "Save"
     :accelerator "CmdOrCtrl+S"
     :click save-file!}

    {:label "Save as..."
     :accelerator "CmdOrCtrl+Shift+S"
     :click save-file-as!}

   {:label "Save HTML as..."
     :accelerator "Alt+CmdOrCtrl+Shift+S"
     :click save-html-as!}]})


(def edit
  {:label "Edit"
   :submenu
   [{:label "Undo"
     :accelerator "CmdOrCtrl+Z"
     :role "undo"}

    {:label "Redo"
     :accelerator "Shift+CmdOrCtrl+Z"
     :role "redo"}

    {:type "separator"}

    {:label "Copy"
     :accelerator "CmdOrCtrl+C"
     :role "copy"}

    {:label "Paste"
     :accelerator "CmdOrCtrl+V"
     :role "paste"}

    {:label "Cut"
     :accelerator "CmdOrCtrl+X"
     :role "cut"}

    {:label "Select All"
     :accelerator "CmdOrCtrl+A"
     :role "selectall"}

    {:type "separator"}

    {:label "Copy All as HTML"
     :accelerator "Shift+CmdOrCtrl+C"
     :click copy-as-html!}

    {:label "Copy All as Formatted Text"
     :accelerator "Shift+Alt+CmdOrCtrl+C"
     :click copy-as-rich-text!}]})


(def window
  {:label "Window"
   :role "window"
   :submenu
   [{:label "Minimize"
     :accelerator "CmdOrCtrl+M"
     :role "minimize"}

    {:label "Close"
     :accelerator "CmdOrCtrl+W"
     :role "close"}]})

(def develop
  {:label "Develop"
   :submenu
   [{:label "Reload"
     :accelerator "CmdOrCtrl+R"
     :click reload!}

    {:label "Toggle DevTools"
     :accelerator "Alt+CmdOrCtrl+I"
     :click toggle-devtools!}]})


(def help
  {:label "Help"
   :role "help"
   :submenu
   [{:label "MarkRight on Github"
     :click #(open-url! "https://github.com/dvcrn/markright")}
    {:label "@davicorn (Twitter)"
     :click #(open-url! "https://twitter.com/davicorn")}]})


(defn create-menu! []
  (.setApplicationMenu
    menu
    (.buildFromTemplate menu
      (clj->js
       (concat
        (if (= (.-platform process) "darwin")
          [markright] [])
        [file
         edit
         window
         develop
         help])))))

(def index (str "file://" (.resolve path (.getAppPath app) "ui" "index.html")))

(defn open-window! []
  (when (nil? @*win*)
    (let [win (BrowserWindow. (clj->js {:width 1200 :height 600}))]
      (reset! *win* win)
      (.loadUrl win index)
      (ipc/set-target! win)
      ;; (.openDevTools win)
      (.on win "closed" (fn [] (do (reset! *win* nil) (swap! backend-state assoc :frontend-loaded false
                                                                                 :content nil
                                                                                 :filepath nil))))
      (.on win "close" (fn [e]
                         (.preventDefault e)
                         (go (if (<! (verify-unsaved-changes))
                               (.destroy @*win*))))))))

(defn is-newer? [current remote]
  (let [first (split current #"\.")
        second (split remote #"\.")
        weight [10000 1000 100]]

    (->>
     [first second]
     (map (fn [group]
            (map #(->
                   %
                   (group)
                   (js/parseInt)
                   (* (weight %)))
                 [0 1 2])))
     (map #(reduce + %))
     (reduce >))))

(defn check-update! []
  ;; xhr call
  ;; https://raw.githubusercontent.com/dvcrn/markright/master/node/package.json
  (let [request (.request https #js {:host "raw.githubusercontent.com"
                                     :path "/dvcrn/markright/master/node/package.json"
                                     :port 443}
                          (fn [response]
                            (let [data (atom {:data (str "")})]
                              (.on response "data" #(swap! data assoc :data (str (@data :data) %)))
                              (.on response "end" (fn []
                                                    (let [remote-package  (.parse js/JSON (@data :data))
                                                          latest-version (.-version remote-package)]
                                                      (if (is-newer? latest-version (.getVersion app))
                                                        (if (= (update-dialog @*win* (.getVersion app) latest-version) 1)
                                                          (.openExternal shell "https://github.com/dvcrn/markright/releases/latest")))))))))]
    (.on request "error" #(.log js/console "Couldn't check for update."))
    (.end request)))


(defn main []
  (.start crash-reporter)

  ;; error listener
  (.on nodejs/process "error"
    (fn [err] (.log js/console err)))

  (.on app "window-all-closed"
    (fn [] (if (not= (.-platform nodejs/process) "darwin")
             (.quit app))))

  ;; need to listen for open-file before the app is ready to not loose information
  ;; though we can't create a new window here if the app is not ready yet,
  ;; so we bind the open-file event twice.
  (.on app "open-file" #(if %2
                          (do
                            (go (if (<! (verify-unsaved-changes)) (load-file! %2)))
                            (.preventDefault %1))))

  (.on app "activate" open-window!)
  (.on app "ready"
    (fn []
      (.on app "open-file" #(if %2 (open-window!)))

      (create-menu!)
      (open-window!)
      (check-update!))))
