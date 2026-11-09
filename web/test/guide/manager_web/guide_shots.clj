(ns manager-web.guide-shots
  "Annotated screenshots for the user guides: outlines the elements a step
   talks about, labels them with an arrow and crops around them."
  (:require [clojure.java.io :as io]
            [manager-web.el :as el]
            [testing.pw :refer [*current-page*]])
  (:import [com.microsoft.playwright Locator Locator$FilterOptions Locator$WaitForOptions Page Page$ScreenshotOptions]
           [com.microsoft.playwright.options Clip ScreenshotType]))

(def out-dir
  "target/guide/img")

(def colors
  {:red "#d32f2f"
   :blue "#1565c0"
   :green "#2e7d32"
   :orange "#e65100"
   :purple "#7b1fa2"})

(def ^:private annotate-js
  "([targets, ctx, pad, dialog, masks, maxHeight]) => {
     const add = (tag, css, parent) => {
       const n = document.createElement(tag);
       n.className = 'guide-mark';
       Object.assign(n.style, {position: 'fixed', zIndex: 2147483647, pointerEvents: 'none', boxSizing: 'border-box'}, css);
       (parent || document.body).appendChild(n);
       return n;
     };
     document.querySelectorAll('.guide-mark').forEach(n => n.remove());
     if (document.activeElement && document.activeElement.blur && !document.activeElement.closest('[role=listbox],[role=menu]')) document.activeElement.blur();
     const hide = document.createElement('style');
     hide.className = 'guide-mark';
     hide.textContent = '.MuiSnackbar-root,.MuiTouchRipple-root,.MuiCircularProgress-root,.MuiLinearProgress-root{display:none !important}' +
                        '*{caret-color:transparent !important;animation:none !important;transition:none !important}';
     document.head.appendChild(hide);
     window.__guideMasks = masks.map(m => {
       const input = m.el.matches('input,textarea') ? m.el : m.el.querySelector('input,textarea');
       if (input) { const old = input.value; input.value = m.text; return () => { input.value = old; }; }
       const walker = document.createTreeWalker(m.el, NodeFilter.SHOW_TEXT);
       let longest = null;
       const secretLike = n => /^\\S{16,}$/.test(n.textContent.trim());
       for (let n = walker.nextNode(); n; n = walker.nextNode()) {
         if (!longest || (secretLike(n) && !secretLike(longest)) ||
             (secretLike(n) === secretLike(longest) && n.textContent.length > longest.textContent.length)) longest = n;
       }
       if (!longest) return () => {};
       const old = longest.textContent; longest.textContent = m.text; return () => { longest.textContent = old; };
     });
     const surface = dialog && targets.length ? (targets[0].els[0].closest('.MuiDialog-paper') || targets[0].els[0].closest('.MuiPaper-root')) : null;
     if (surface) ctx = surface;
     const extents = [];
     const ctxRect = ctx && ctx.getBoundingClientRect();
     if (ctxRect) extents.push(ctxRect);
     const ns = 'http://www.w3.org/2000/svg';
     const svg = document.createElementNS(ns, 'svg');
     svg.setAttribute('class', 'guide-mark');
     Object.assign(svg.style, {position: 'fixed', left: 0, top: 0, width: '100vw', height: '100vh',
                               zIndex: 2147483647, pointerEvents: 'none'});
     document.body.appendChild(svg);
     const defs = document.createElementNS(ns, 'defs');
     svg.appendChild(defs);
     const outlined = el => {
       const r = el.getBoundingClientRect();
       const isField = el.matches('input,textarea,[role=combobox],.MuiSelect-select') || (r.height < 140 && el.querySelector('input,textarea,[role=combobox]'));
       if (!isField) return r;
       let node = el, label = null;
       for (let i = 0; i < 4 && node && !label; i++, node = node.parentElement) {
         label = Array.from(node.children).find(c => c.matches('label,.MuiInputLabel-root')) || null;
         if (label) break;
       }
       const base = (label && label.parentElement !== el ? label.parentElement.querySelector('.MuiInputBase-root') : null) || el;
       const b = base.getBoundingClientRect();
       const rects = [b].concat(label ? [label.getBoundingClientRect()] : []);
       return {left: Math.min(...rects.map(x => x.left), b.left), top: Math.min(...rects.map(x => x.top)) - (label ? 3 : 0),
               right: b.right, bottom: b.bottom};
     };
     const boxes = targets.map(t => {
       const rs = t.els.map(outlined);
       return {left: Math.min(...rs.map(r => r.left)) - 5, top: Math.min(...rs.map(r => r.top)) - 5,
               right: Math.max(...rs.map(r => r.right)) + 5, bottom: Math.max(...rs.map(r => r.bottom)) + 5};
     });
     const obstacles = boxes.slice();
     const textRects = [];
     const visible = r => r.width && r.height && r.bottom > 0 && r.top < window.innerHeight;
     const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
     for (let node = walker.nextNode(); node; node = walker.nextNode()) {
       if (!node.textContent.trim() || node.parentElement.closest('.guide-mark')) continue;
       const range = document.createRange();
       range.selectNodeContents(node);
       Array.from(range.getClientRects()).filter(visible).forEach(r => textRects.push(r));
     }
     document.querySelectorAll('input,textarea,svg,img,button').forEach(el => {
       if (el.closest('.guide-mark')) return;
       const r = el.getBoundingClientRect();
       if (visible(r)) textRects.push(r);
     });
     const overlap = (a, b) => Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left)) *
                               Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
     const inside = (p, r) => p[0] >= r.left && p[0] <= r.right && p[1] >= r.top && p[1] <= r.bottom;
     const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
     targets.forEach((t, i) => {
       const box = boxes[i];
       add('div', {left: box.left + 'px', top: box.top + 'px', width: (box.right - box.left) + 'px',
                   height: (box.bottom - box.top) + 'px', border: '3px solid ' + t.color, borderRadius: '8px',
                   boxShadow: '0 0 0 4px ' + t.color + '33'});
       extents.push(box);
     });
     const lines = [];
     targets.forEach((t, i) => {
       const box = boxes[i];
       const lab = add('div', {background: t.color, color: '#fff', font: '600 14px/1.3 system-ui, sans-serif',
                               padding: '6px 10px', borderRadius: '6px', maxWidth: '260px',
                               boxShadow: '0 2px 6px rgba(0,0,0,0.25)'});
       lab.textContent = t.label;
       const lw = lab.offsetWidth, lh = lab.offsetHeight;
       const cx = (box.left + box.right) / 2, cy = (box.top + box.bottom) / 2;
       const ends = (side, x, y) => {
         const from = {left: [x + lw, y + lh / 2], right: [x, y + lh / 2],
                       top: [x + lw / 2, y + lh], bottom: [x + lw / 2, y]}[side];
         const to = {left: [box.left - 2, clamp(from[1], box.top + 6, box.bottom - 6)],
                     right: [box.right + 2, clamp(from[1], box.top + 6, box.bottom - 6)],
                     top: [clamp(from[0], box.left + 6, box.right - 6), box.top - 2],
                     bottom: [clamp(from[0], box.left + 6, box.right - 6), box.bottom + 2]}[side];
         return [from, to];
       };
       const place = (side, gap, shift) => {
         const p = {left:   [box.left - gap - lw, cy - lh / 2 + shift * lh * 1.5],
                    right:  [box.right + gap, cy - lh / 2 + shift * lh * 1.5],
                    top:    [cx - lw / 2 + shift * lw * 0.6, box.top - gap - lh],
                    bottom: [cx - lw / 2 + shift * lw * 0.6, box.bottom + gap]}[side];
         const x = clamp(p[0], 4, window.innerWidth - lw - 4);
         const y = clamp(p[1], 4, window.innerHeight - lh - 4);
         const [from, to] = ends(side, x, y);
         return {side, x, y, from, to, rect: {left: x - 3, top: y - 3, right: x + lw + 3, bottom: y + lh + 3}};
       };
       const crossing = c => {
         let hits = 0;
         for (let k = 1; k < 16; k++) {
           const p = [c.from[0] + (c.to[0] - c.from[0]) * k / 16, c.from[1] + (c.to[1] - c.from[1]) * k / 16];
           if (textRects.some(r => inside(p, r))) hits += 1;
           if (obstacles.some(o => o !== box && inside(p, o))) hits += 3;
         }
         return hits;
       };
       const sides = [t.side, ...['bottom', 'top', 'right', 'left'].filter(s => s !== t.side)];
       let best = null;
       sides.forEach((side, si) => [t.distance, t.distance + 40, t.distance + 90].forEach((gap, gi) =>
         [0, -1, 1].forEach((shift, hi) => {
           const c = place(side, gap, shift);
           const length = Math.hypot(c.to[0] - c.from[0], c.to[1] - c.from[1]);
           const score = obstacles.reduce((acc, o) => acc + 6 * overlap(c.rect, o), 0) +
                         textRects.reduce((acc, o) => acc + overlap(c.rect, o), 0) +
                         crossing(c) * 1200 + length * 3 +
                         si * 400 + gi * 100 + hi * 200;
           if (!best || score < best.score) best = Object.assign(c, {score});
         })));
       const {x: lx, y: ly, from, to} = best;
       lab.style.left = lx + 'px';
       lab.style.top = ly + 'px';
       obstacles.push(best.rect);
       extents.push({left: lx, top: ly, right: lx + lw, bottom: ly + lh});
       const m = document.createElementNS(ns, 'marker');
       m.setAttribute('id', 'guide-arrow-' + i);
       m.setAttribute('viewBox', '0 0 10 10');
       m.setAttribute('refX', '8'); m.setAttribute('refY', '5');
       m.setAttribute('markerWidth', '5'); m.setAttribute('markerHeight', '5');
       m.setAttribute('orient', 'auto-start-reverse');
       const tip = document.createElementNS(ns, 'path');
       tip.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z');
       tip.setAttribute('fill', t.color);
       m.appendChild(tip);
       defs.appendChild(m);
       const line = document.createElementNS(ns, 'line');
       line.setAttribute('x1', from[0]); line.setAttribute('y1', from[1]);
       line.setAttribute('x2', to[0]); line.setAttribute('y2', to[1]);
       line.setAttribute('stroke', t.color);
       line.setAttribute('stroke-width', '3');
       line.setAttribute('stroke-linecap', 'round');
       line.setAttribute('marker-end', 'url(#guide-arrow-' + i + ')');
       svg.appendChild(line);
     });
     let l = Math.min(...extents.map(e => e.left)) - pad[3];
     let t = Math.min(...extents.map(e => e.top)) - pad[0];
     let r = Math.max(...extents.map(e => e.right)) + pad[1];
     let b = Math.max(...extents.map(e => e.bottom)) + pad[2];
     l = Math.max(0, l); t = Math.max(0, t);
     r = Math.min(window.innerWidth, r); b = Math.min(window.innerHeight, b);
     if (maxHeight) b = Math.min(b, t + maxHeight);
     return [l, t, r - l, b - t];
   }")

(def ^:private restore-js
  "() => {
     (window.__guideMasks || []).forEach(f => f());
     window.__guideMasks = [];
     document.querySelectorAll('.guide-mark').forEach(n => n.remove());
   }")

(def ^:private scroll-js
  "el => {
     const r = el.getBoundingClientRect();
     el.scrollIntoView({behavior: 'instant', block: r.height < window.innerHeight - 120 ? 'center' : 'start'});
     if (r.height >= window.innerHeight - 120) window.scrollBy({top: -60, behavior: 'instant'});
     return new Promise(res => requestAnimationFrame(() => requestAnimationFrame(res)));
   }")

(defn locate
  "The first visible element of target: an el-id (vector), a CSS selector or a locator."
  ^Locator [target]
  (let [^Page page
        *current-page*

        loc
        (cond
          (instance? Locator target)
          target

          (string? target)
          (.first (.locator page ^String target))

          :else
          (.first (.filter (.locator page ^String (el/sel target))
                           (.setVisible (Locator$FilterOptions.) true))))]
    (.waitFor ^Locator loc (.setTimeout (Locator$WaitForOptions.) 15000.0))
    loc))

(defn- normalize-mark
  [idx mark]
  (let [palette
        [:red :blue :green :orange :purple]]
    (merge {:side :left
            :distance 40
            :color (nth palette (mod idx (count palette)))}
           mark)))

(defn- handles
  [{:keys [target around]}]
  (mapv #(.elementHandle (locate %)) (or around [target])))

(defn shot!
  "Saves `name`.png under `out-dir` and returns the name.

   `:marks` - maps {:target el-id (or :around [el-id …] for one box around
     several) :label \"What it is\" :side :left|:right|:top|:bottom
     :color :red|:blue|… :distance px}; each is outlined and labelled with an
     arrow that avoids text. An input is outlined with its floating label.
   `:ctx` - the element the crop is built around (marks and labels are always
     inside the crop). `:full` - keep the whole viewport.
   `:pad` - pixels kept around the crop, a number or [top right bottom left].
   `:max-height` - cuts the crop off below this height.
   `:dialog` - crop around the dialog (or card) the first mark sits on.
   `:mask` - [[target text] …] shown instead of the real value (secrets).
   `:scroll` false leaves the page where it is.
   Nothing but a masked secret differs from what the page shows."
  [name {:keys [marks ctx pad full scroll dialog mask max-height]
         :or {pad 16 scroll true}}]
  (io/make-parents (io/file out-dir "x"))
  (let [^Page page
        *current-page*

        marks
        (map-indexed normalize-mark marks)

        ctx-loc
        (when ctx (locate ctx))

        mark-handles
        (mapv handles marks)

        _
        (when scroll
          (if ctx-loc
            (.evaluate ^Locator ctx-loc scroll-js)
            (.evaluate page scroll-js (or (ffirst mark-handles) (.elementHandle (.locator page "body"))))))

        targets
        (mapv (fn [m els]
                {"els" els
                 "label" (:label m)
                 "side" (clojure.core/name (:side m))
                 "distance" (int (:distance m))
                 "color" (get colors (:color m) (:color m))})
              marks mark-handles)

        masks
        (mapv (fn [[target text]] {"el" (.elementHandle (locate target)) "text" text}) mask)

        [l t w h]
        (.evaluate page annotate-js
                   [targets
                    (when ctx-loc (.elementHandle ^Locator ctx-loc))
                    (mapv int (if (number? pad) [pad pad pad pad] pad))
                    (boolean dialog)
                    masks
                    (when max-height (int max-height))])

        opts
        (cond-> (doto (Page$ScreenshotOptions.)
                  (.setPath (.toPath (io/file out-dir (str name ".png"))))
                  (.setType ScreenshotType/PNG))
          (and (not full) (or ctx-loc (seq mark-handles)))
          (.setClip (Clip. (double l) (double t) (double w) (double h))))]
    (try
      (.screenshot page opts)
      (finally
        (.evaluate page restore-js)))
    (println "  [guide shot]" name (if (seq mask) "(masked a secret)" ""))
    name))
