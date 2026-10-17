/* Theme switch for the manual: light / dark / system.
   Loaded from <head> so the saved choice applies before the first paint. "system" removes data-theme and lets
   the prefers-color-scheme rules in style.css decide. The choice is kept per browser in localStorage. */
(function () {
  var KEY = "teslable-manual-theme";
  var root = document.documentElement;

  function load() {
    try { return localStorage.getItem(KEY) || "system"; } catch (e) { return "system"; }
  }

  function save(choice) {
    try { localStorage.setItem(KEY, choice); } catch (e) { /* private mode: keep it for this page only */ }
  }

  function apply(choice) {
    if (choice === "light" || choice === "dark") root.setAttribute("data-theme", choice);
    else root.removeAttribute("data-theme");
    var buttons = document.querySelectorAll(".theme-switch [data-theme-choice]");
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].setAttribute("aria-checked", String(buttons[i].getAttribute("data-theme-choice") === choice));
    }
  }

  root.classList.add("js");
  apply(load());

  document.addEventListener("DOMContentLoaded", function () {
    apply(load());
    var group = document.querySelector(".theme-switch");
    if (!group) return;
    group.addEventListener("click", function (event) {
      var button = event.target.closest("[data-theme-choice]");
      if (!button) return;
      var choice = button.getAttribute("data-theme-choice");
      save(choice);
      apply(choice);
    });
  });

  /* Another tab changed the theme */
  window.addEventListener("storage", function (event) {
    if (event.key === KEY) apply(load());
  });
})();
