window.AirOpsPage.register(function(){
 var m=document.querySelector('#calibrate-modal');if(!m)return;
 document.querySelectorAll('[data-calibrate]').forEach(function(b){b.addEventListener('click',function(){
  var d=b.dataset;
  m.querySelector('form').action='/admin/servers/'+d.id+'/calibrate-traffic';
  m.querySelector('[data-calibrate-name]').textContent=d.name;
  m.querySelector('[data-calibrate-unit]').textContent=d.unit;
  m.querySelector('[name=used]').value='';
 });});
 m.addEventListener('shown.bs.modal',function(){m.querySelector('[name=used]').focus();});
});
