/* Theme switch for the manual: light / dark / system.
   Loaded from <head> so the saved choice applies before the first paint. "system" removes data-theme and lets
   the prefers-color-scheme rules in style.css decide. The choice is kept per browser in localStorage.
   The buttons form a radio group: one tab stop, arrow keys move the selection. */
(function () {
  var KEY = "teslable-manual-theme";
  var CHOICES = ["light", "dark", "system"];
  var root = document.documentElement;

  function load() {
    var value = null;
    try { value = localStorage.getItem(KEY); } catch (e) { /* storage blocked: fall back to system */ }
    return CHOICES.indexOf(value) >= 0 ? value : "system";
  }

  function save(choice) {
    try { localStorage.setItem(KEY, choice); } catch (e) { /* private mode: keep it for this page only */ }
  }

  function buttons() {
    return document.querySelectorAll(".theme-switch [data-theme-choice]");
  }

  function apply(choice) {
    if (choice === "light" || choice === "dark") root.setAttribute("data-theme", choice);
    else root.removeAttribute("data-theme");
    var list = buttons();
    for (var i = 0; i < list.length; i++) {
      var on = list[i].getAttribute("data-theme-choice") === choice;
      list[i].setAttribute("aria-checked", String(on));
      list[i].tabIndex = on ? 0 : -1;
    }
  }

  function choose(choice, focus) {
    save(choice);
    apply(choice);
    if (focus) document.querySelector('.theme-switch [data-theme-choice="' + choice + '"]').focus();
  }

  root.classList.add("js");
  apply(load());

  document.addEventListener("DOMContentLoaded", function () {
    apply(load());
    var group = document.querySelector(".theme-switch");
    if (!group) return;
    group.addEventListener("click", function (event) {
      var button = event.target.closest("[data-theme-choice]");
      if (button) choose(button.getAttribute("data-theme-choice"), false);
    });
    group.addEventListener("keydown", function (event) {
      var step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[event.key];
      if (!step) return;
      event.preventDefault();
      var current = group.querySelector('[aria-checked="true"]');   /* not load(): storage may be blocked */
      var at = current ? CHOICES.indexOf(current.getAttribute("data-theme-choice")) : CHOICES.indexOf("system");
      var next = (at + step + CHOICES.length) % CHOICES.length;
      choose(CHOICES[next], true);
    });
  });

  /* Another tab changed the theme or cleared storage */
  window.addEventListener("storage", function (event) {
    if (event.key === KEY || event.key === null) apply(load());
  });
})();
