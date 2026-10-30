window.AirOpsPage.register(function(){
 var source=document.querySelector('#user_source');
 if(source){function update(){document.querySelector('[data-new-user]').hidden=source.value!=='new';document.querySelector('[data-existing-user]').hidden=source.value==='new';}source.addEventListener('change',update);update();}
 document.querySelectorAll('form').forEach(function(form){form.addEventListener('invalid',function(e){var pane=e.target.closest('.tab-pane');if(pane&&!pane.classList.contains('active')){var tab=document.querySelector('[data-bs-target="#'+pane.id+'"]');if(tab){tab.click();}}},true);});

 var pw=document.querySelector('#new_password');
 function gen(){var c='abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789',a=new Uint32Array(12);crypto.getRandomValues(a);return Array.from(a,function(n){return c[n%c.length];}).join('');}
 if(pw){if(!pw.value){pw.value=gen();}var g=document.querySelector('[data-gen-password]');g.addEventListener('click',function(){pw.value=gen();});}
 var gu=document.querySelector('[data-gen-uuid]');
 if(gu){gu.addEventListener('click',function(){if(!confirm('重新生成 UUID 后，客户端需更新配置，保存后相关节点需重新部署。继续？'))return;
  var b=crypto.getRandomValues(new Uint8Array(16));b[6]=b[6]&15|64;b[8]=b[8]&63|128;
  var h=Array.from(b,function(n){return n.toString(16).padStart(2,'0');}).join('');
  document.querySelector('#uuid').value=h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20);});}
 var to=document.querySelector('#renew_to_date');
 function pad(n){return String(n).padStart(2,'0');}
 function fmt(d){return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+'T'+pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds());}
 function addMonths(base,n){var day=base.getDate();base.setDate(1);base.setMonth(base.getMonth()+n);
  base.setDate(Math.min(day,new Date(base.getFullYear(),base.getMonth()+1,0).getDate()));return base;}
 document.querySelectorAll('[data-renew-months]').forEach(function(b){b.addEventListener('click',function(){
  var base=new Date(to.dataset.base||''),now=new Date();if(isNaN(base)||base<now){base=now;}
  to.value=fmt(addMonths(base,Number(b.dataset.renewMonths)));});});
 // 开户时长从开始时间起算
 document.querySelectorAll('[data-term-months]').forEach(function(b){b.addEventListener('click',function(){
  var base=new Date(document.querySelector('#from_date').value||'');if(isNaN(base)){base=new Date();}
  document.querySelector('#to_date').value=fmt(addMonths(base,Number(b.dataset.termMonths)));});});
 // 按所选标签统计可用节点（标签并集），为 0 时保存前二次确认
 var hint=document.querySelector('[data-tag-nodes]');
 if(hint){var form=hint.closest('form');
  function count(){var ids=new Set();form.querySelectorAll('[data-tag-id]').forEach(function(p){if(p.querySelector('input[name=tags]').checked){(p.dataset.nodeIds||'').split(' ').filter(Boolean).forEach(function(id){ids.add(id);});}});return ids.size;}
  function show(){var n=count();hint.textContent=n?'将可用 '+n+' 个节点':'未匹配任何可用节点，客户订阅将为空';hint.classList.toggle('text-danger',!n);}
  form.addEventListener('change',function(e){if(e.target.name==='tags'){show();}});show();
  form.addEventListener('submit',function(e){if(!count()&&!confirm('所选标签未匹配任何可用节点，客户订阅将为空。仍要保存？')){e.preventDefault();}});}
});
