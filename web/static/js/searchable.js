// 搜索选择框：select[data-search] 用 Tom Select；部署位 + 连接域名联动见 account_bindings。
window.AirOpsPage.register(function () {
  var cfg = {maxOptions: null, allowEmptyOption: true, plugins: [], render: {no_results: function () { return '<div class="no-results">无匹配项</div>'; }}};
  document.querySelectorAll('select[data-search]').forEach(function (el) { el._ts = new TomSelect(el, cfg); });

  document.querySelectorAll('form').forEach(function (form) {
    var place = form.querySelector('select[data-placement]'), rec = form.querySelector('select[data-record]');
    if (!place || !rec) { return; }
    var ts = new TomSelect(rec, Object.assign({}, cfg, {placeholder: rec.dataset.empty}));
    var current = rec.dataset.current || '';
    function fill() {
      var opt = place.selectedOptions[0], list = [];
      try { list = JSON.parse((opt && opt.dataset.records) || 'null') || []; } catch (e) {}
      ts.clear(true); ts.clearOptions();
      ts.addOption({value: '', text: rec.dataset.empty});
      list.forEach(function (r) { ts.addOption({value: String(r.ID), text: r.Name}); });
      if (current && !list.some(function (r) { return String(r.ID) === current; })) {
        ts.addOption({value: current, text: rec.dataset.currentName || current});
      }
      ts.setValue(current, true);
    }
    place.addEventListener('change', function () { current = ''; fill(); });
    rec.addEventListener('change', function () { current = rec.value; });
    fill();
    var link = form.querySelector('[data-toggle-new-domain]'), box = form.querySelector('[data-new-domain]');
    if (link && box) {
      var name = box.querySelector('input[name=new_name]');
      link.addEventListener('click', function (e) {
        e.preventDefault(); box.hidden = !box.hidden;
        if (box.hidden) { name.value = ''; } else { name.focus(); }
      });
    }
  });
  return function () {
    document.querySelectorAll('select').forEach(function (el) { if (el.tomselect) el.tomselect.destroy(); });
  };
});
