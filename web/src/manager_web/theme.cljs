(ns manager-web.theme
  "The only place styling is defined: palette, typography, component
   defaults and the named sx fragments views use. Views reference theme
   tokens (\"text.secondary\", \"status.running\") or the fragments below,
   never raw colors or sizes.")

(def sans "\"IBM Plex Sans\", system-ui, sans-serif")
(def mono "\"IBM Plex Mono\", ui-monospace, monospace")

(def drawer-width 236)

(def ^:private light
  {:primary {:main "#0B6E69" :contrastText "#FFFFFF"}
   :secondary {:main "#4A5866"}
   :warning {:main "#A15C07"}
   :error {:main "#B42318"}
   :success {:main "#2F7D4F"}
   :info {:main "#2D63A6"}
   :background {:default "#F3F5F6" :paper "#FFFFFF"}
   :text {:primary "#18212B" :secondary "#5B6875"}
   :divider "#DDE2E6"})

(def ^:private dark
  {:primary {:main "#5CC2B8" :contrastText "#06201E"}
   :secondary {:main "#A3B1BE"}
   :warning {:main "#E0A04A"}
   :error {:main "#F07A6E"}
   :success {:main "#6CC08F"}
   :info {:main "#7EA8E0"}
   :background {:default "#0F1417" :paper "#161D21"}
   :text {:primary "#E6EBEF" :secondary "#9AA7B3"}
   :divider "#2A343B"})

(def ^:private typography
  {:fontFamily sans
   :fontSize 14
   :h1 {:fontSize "1.75rem" :fontWeight 600 :lineHeight 1.25 :letterSpacing "-0.01em"}
   :h2 {:fontSize "1.125rem" :fontWeight 600 :lineHeight 1.35}
   :h3 {:fontSize "1rem" :fontWeight 600 :lineHeight 1.4}
   :h5 {:fontSize "1.5rem" :fontWeight 600}
   :h6 {:fontSize "1rem" :fontWeight 600}
   :subtitle1 {:fontWeight 500}
   :subtitle2 {:fontWeight 600}
   :body1 {:fontSize "0.9375rem" :lineHeight 1.55}
   :body2 {:fontSize "0.875rem" :lineHeight 1.5}
   :button {:textTransform "none" :fontWeight 500 :letterSpacing 0}
   :mono {:fontFamily mono :fontSize "0.8125rem"}
   :label {:fontSize "0.8125rem" :fontWeight 500 :color "var(--mui-palette-text-secondary)"}})

(def ^:private components
  {:MuiCssBaseline {:styleOverrides {"code, pre, kbd" {:fontFamily mono}
                                     :a {:color "var(--mui-palette-primary-main)"}}}
   :MuiTypography {:defaultProps {:variantMapping {:mono "span" :label "span"}}}
   :MuiPaper {:defaultProps {:variant "outlined"}}
   :MuiCard {:defaultProps {:variant "outlined"}}
   :MuiAppBar {:defaultProps {:elevation 0 :color "inherit"}
               :styleOverrides {:root {:borderBottom "1px solid var(--mui-palette-divider)"}}}
   :MuiButton {:defaultProps {:disableElevation true}}
   :MuiChip {:defaultProps {:size "small" :variant "outlined"}
             :styleOverrides {:root {:borderRadius 6 :fontWeight 500}}}
   :MuiTabs {:styleOverrides {:root {:borderBottom "1px solid var(--mui-palette-divider)" :minHeight 40}}}
   :MuiTab {:styleOverrides {:root {:minHeight 40 :paddingInline 12 :minWidth 0 :fontWeight 500}}}
   :MuiTable {:defaultProps {:size "small"}}
   :MuiTableCell {:styleOverrides {:root {:paddingBlock 10}
                                   :head {:fontSize "0.8125rem"
                                          :fontWeight 600
                                          :color "var(--mui-palette-text-secondary)"
                                          :whiteSpace "nowrap"}}}
   :MuiTableRow {:styleOverrides {:root {"&:last-child td" {:borderBottom 0}}}}
   :MuiAlert {:styleOverrides {:root {:alignItems "flex-start"}
                               :message {:overflowWrap "anywhere"}}}
   :MuiTooltip {:defaultProps {:arrow true}}
   :MuiLink {:defaultProps {:underline "hover"}}
   :MuiListItemButton {:styleOverrides {:root {:borderRadius 6
                                               :marginInline 8
                                               :paddingBlock 6
                                               "&.Mui-selected" {:color "var(--mui-palette-primary-main)"
                                                                 :fontWeight 600}
                                               "&.Mui-selected .MuiListItemIcon-root" {:color "var(--mui-palette-primary-main)"}}}}
   :MuiListItemIcon {:styleOverrides {:root {:minWidth 34}}}
   :MuiDialogTitle {:styleOverrides {:root {:fontSize "1.125rem" :fontWeight 600}}}
   :MuiAccordion {:defaultProps {:disableGutters true :variant "outlined"}
                  :styleOverrides {:root {"&::before" {:display "none"}}}}})

(def theme
  {:cssVariables {:colorSchemeSelector "media"}
   :colorSchemes {:light {:palette light}
                  :dark {:palette dark}}
   :shape {:borderRadius 8}
   :typography typography
   :components components})

(def status-colors
  "MUI color names per kind of status, keyed by kebab-case status."
  {:session {:running "success"
             :starting "info"
             :stopped "default"
             :error "error"}
   :run {:queued "default"
         :preparing "info"
         :running "info"
         :awaiting-result "info"
         :finalizing "info"
         :following-ci "info"
         :completed "success"
         :failed "error"
         :cancelled "warning"}
   :build {:waiting "default"
           :running "info"
           :passed "success"
           :failed "error"
           :timeout "warning"}
   :stage {:success "success"
           :failed "error"
           :in-progress "info"
           :paused-pending-input "warning"
           :aborted "warning"}
   :verdict {:approve "success"
             :approve-with-nits "success"
             :request-changes "error"}
   :severity {:blocker "error"
              :major "warning"
              :minor "info"
              :question "secondary"
              :nit "default"}})

(def ^:private code-block
  {:m 0 :p 1.5 :bgcolor "action.hover" :borderRadius 1 :overflow "auto" :typography "mono" :whiteSpace "pre"})

(def sx
  "Named sx fragments shared by views."
  {:page-stack {:gap 4}
   :app-frame {:display "flex" :minHeight "100vh"}
   :sidebar-nav {:flexGrow 1 :overflowY "auto"}
   :loading {:display "grid" :placeItems "center" :py 6}
   :centered-screen {:display "grid" :placeItems "center" :minHeight "100vh" :px 2}
   :login-card {:p 4 :width "100%" :maxWidth 420}
   :brand {:alignItems "center" :color "text.primary" :textDecoration "none" :cursor "pointer"}
   :brand-mark {:width 30 :height 30 :bgcolor "primary.main" :color "primary.contrastText"}
   :brand-mark-large {:bgcolor "primary.main" :color "primary.contrastText"}
   :drawer {:width drawer-width :flexShrink 0 "& .MuiDrawer-paper" {:width drawer-width}}
   :drawer-temporary {"& .MuiDrawer-paper" {:width drawer-width}}
   :sidebar-footer {:p 2 :borderTop 1 :borderColor "divider" :alignItems "center"}
   :avatar-small {:width 32 :height 32 :fontSize "0.875rem"}
   :main {:flexGrow 1 :minWidth 0}
   :container {:py {:xs 3 :md 5}}
   :back-link {:display "inline-flex" :alignItems "center" :gap 0.5 :cursor "pointer"}
   :header-description {:mt 0.5 :maxWidth 760}
   :header-actions {:flexShrink 0 :flexWrap "wrap"}
   :header-meta {:alignItems "center" :flexWrap "wrap" :color "text.secondary"}
   :section-header {:alignItems "flex-end"}
   :empty-state {:p 4 :textAlign "center" :borderStyle "dashed"}
   :empty-state-text {:mt 0.5 :maxWidth 520 :mx "auto"}
   :detail-label {:width {:xs 104 :sm 180} :color "text.secondary" :verticalAlign "top"}
   :detail-value {:whiteSpace "pre-wrap" :overflowWrap "anywhere"}
   :stat {:px 2 :py 1.5}
   :stat-grid {:display "grid" :gap 1.5 :gridTemplateColumns "repeat(auto-fill, minmax(160px, 1fr))"}
   :code-block code-block
   :code-block-short (assoc code-block :maxHeight 320)
   :two-columns {:display "grid" :gap 3 :gridTemplateColumns {:xs "1fr" :md "1fr 1fr"} :alignItems "start"}
   :row {:alignItems "center" :flexWrap "wrap"}
   :list-row {:px 2 :py 2 :alignItems {:sm "center"} "& + &" {:borderTop 1 :borderColor "divider"}}
   :list-row-dense {:px 2 :py 1 :alignItems "center" "& + &" {:borderTop 1 :borderColor "divider"}}
   :icon-tile {:bgcolor "action.hover" :color "primary.main"}
   :wide-only {:display {:xs "none" :md "table-cell"}}
   :wide-only-nowrap {:display {:xs "none" :md "table-cell"} :whiteSpace "nowrap"}
   :grow {:flexGrow 1 :minWidth 0}
   :no-shrink {:flexShrink 0}
   :error-list {:m 0 :pl 2.5}
   :intro {:p 2.5 :position "relative"}
   :intro-close {:position "absolute" :top 8 :right 8}
   :intro-steps {:display "grid" :gap 2.5 :gridTemplateColumns {:xs "1fr" :sm "1fr 1fr" :lg "repeat(4, 1fr)"}
                 :m 0 :p 0 :listStyle "none"}
   :step-number {:minWidth 16}
   :chip-gap {:ml 1}
   :min-width-0 {:minWidth 0}
   :spacer {:flexGrow 1}
   :harness-grid {:display "grid" :gap 2 :gridTemplateColumns {:xs "1fr" :lg "1fr 1fr"} :alignItems "start"}
   :card-grid {:display "grid" :gap 2 :gridTemplateColumns "repeat(auto-fill, minmax(300px, 1fr))"}
   :definition-grid {:display "grid" :gap 2 :gridTemplateColumns "repeat(auto-fill, minmax(320px, 1fr))"}
   :full-height {:height "100%"}
   :definitions {:m 0 :display "grid" :gridTemplateColumns "max-content 1fr" :columnGap 2 :rowGap 0.75
                 :typography "body2"}
   :definition-value {:m 0}
   :budget-bar {:height 8 :borderRadius 4}
   :filter-select {:minWidth 180}
   :progress-note {:mt 2 :textAlign "center"}
   :result-paper {:p {:xs 2 :md 3}}
   :setup-paper {:p 3 :maxWidth 720}
   :setup-later {:mt 2}
   :muted {:color "text.secondary"}
   :status-dot {:fontSize "8px !important"}
   :panel {:p 2}
   :panel-roomy {:p 2.5}
   :scroll-x {:overflowX "auto"}
   :nowrap {:whiteSpace "nowrap"}
   :strong {:fontWeight 600}
   :strong-link {:fontWeight 500}
   :start-aligned {:alignItems "flex-start"}
   :end-aligned-row {:justifyContent "flex-end" :alignItems "center"}
   :responsive-row {:alignItems {:sm "center"}}
   :baseline-row {:alignItems "baseline" :minWidth 0}
   :centered-row {:alignItems "center" :minWidth 0}
   :spaced-top {:mt 1}
   :form-top {:pt 1}
   :icon-gap {:mr 1}
   :outcome {:mt 0.75}
   :team-group-link {:display "block" :mb 0.75}
   :time-column {:minWidth 76}
   :type-column {:minWidth 92 :fontWeight 600}
   :timeline {:maxHeight 480 :overflowY "auto"}
   :timeline-warning {:flexGrow 1 :minWidth 0 :color "warning.main"}
   :finding-header {:alignItems "center" :mb 1 :flexWrap "wrap"}
   :patch-paper {:p 2 :overflow "auto" :maxHeight 600 :width "100%"}
   :patch {:m 0 :typography "mono"}
   :patch-line {:display "block"}
   :patch-line-diff {:display "block" :fontWeight 600}
   :patch-line-file {:display "block" :color "text.secondary"}
   :patch-line-added {:display "block" :color "success.main"}
   :patch-line-removed {:display "block" :color "error.main"}
   :patch-line-hunk {:display "block" :color "info.main"}
   :markdown-document {"& h1, & h2, & h3" {:mt 2.5 :mb 1 :lineHeight 1.3 :fontWeight 600}
                       "& h1" {:fontSize "1.375rem"}
                       "& h2" {:fontSize "1.175rem"}
                       "& h3" {:fontSize "1rem"}
                       "& > :first-child" {:mt 0}
                       :maxWidth 820
                       "& p, & li" {:lineHeight 1.6}
                       "& table" {:borderCollapse "collapse" :width "100%" :my 2 :fontSize "0.9rem"}
                       "& th, & td" {:border 1 :borderColor "divider" :p 1 :textAlign "left" :verticalAlign "top"}
                       "& th" {:bgcolor "action.hover"}
                       "& code" {:typography "mono" :bgcolor "action.hover" :px 0.5 :borderRadius 0.5}
                       "& pre" {:bgcolor "action.hover" :p 2 :borderRadius 1 :overflowX "auto"}
                       "& pre code" {:bgcolor "transparent" :p 0}}})
