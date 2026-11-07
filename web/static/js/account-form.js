window.AirOpsPage.register(function(){
 var source=document.querySelector('#user_source');
 if(source){function update(){document.querySelector('[data-new-user]').hidden=source.value!=='new';document.querySelector('[data-existing-user]').hidden=source.value==='new';}source.addEventListener('change',update);update();}

 var pw=document.querySelector('#new_password');
 function gen(){var c='abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789',a=new Uint32Array(12);crypto.getRandomValues(a);return Array.from(a,function(n){return c[n%c.length];}).join('');}
 if(pw){if(!pw.value){pw.value=gen();}var g=document.querySelector('[data-gen-password]');g.addEventListener('click',function(){pw.value=gen();});}
 var gu=document.querySelector('[data-gen-uuid]');
 if(gu){gu.addEventListener('click',function(){if(!confirm('重新生成 UUID 后，客户端需更新配置，保存后相关节点需重新部署。继续？'))return;
  var b=crypto.getRandomValues(new Uint8Array(16));b[6]=b[6]&15|64;b[8]=b[8]&63|128;
  var h=Array.from(b,function(n){return n.toString(16).padStart(2,'0');}).join('');
  document.querySelector('#uuid').value=h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20);});}
 // 日期按上海时区计算：Date 的 UTC 字段即上海墙钟，与服务端 store.AddMonths 的月末钳制一致
 var SH=8*3600e3;
 function pad(n){return String(n).padStart(2,'0');}
 function shNow(){return new Date(Date.now()+SH);}
 function shParse(v){var d=new Date((v||'').replace(' ','T').slice(0,16)+'Z');return isNaN(d)?null:d;}
 function fmt(d){return d.getUTCFullYear()+'-'+pad(d.getUTCMonth()+1)+'-'+pad(d.getUTCDate())+'T'+pad(d.getUTCHours())+':'+pad(d.getUTCMinutes());}
 function addMonths(base,n){var day=base.getUTCDate();base.setUTCDate(1);base.setUTCMonth(base.getUTCMonth()+n);
  base.setUTCDate(Math.min(day,new Date(Date.UTC(base.getUTCFullYear(),base.getUTCMonth()+1,0)).getUTCDate()));return base;}
 // 续期从 max(原到期, 当前) 起算
 function renewTo(base,n){var b=shParse(base),now=shNow();if(!b||b<now){b=now;}return fmt(addMonths(b,n));}
 var to=document.querySelector('#renew_to_date');
 document.querySelectorAll('[data-renew-months]').forEach(function(b){b.addEventListener('click',function(){
  to.value=renewTo(to.dataset.base,Number(b.dataset.renewMonths));});});
 // 开户时长从开始时间起算
 document.querySelectorAll('[data-term-months]').forEach(function(b){b.addEventListener('click',function(){
  var base=shParse(document.querySelector('#from_date').value)||shNow();
  document.querySelector('#to_date').value=fmt(addMonths(base,Number(b.dataset.termMonths)));});});
 // 账户列表续期弹窗：按行数据填充，金额按上笔流水月单价折算（月数不可知时原样带出）
 var modal=document.querySelector('#accountRenewModal');
 if(modal){var form=modal.querySelector('form'),row={};
  var amount=form.querySelector('[name=amount]'),method=form.querySelector('[name=payment_method]'),date=form.querySelector('[name=to_date]');
  function pick(n){row.n=n;date.value=renewTo(row.to,n);
   form.querySelectorAll('[data-list-renew-months]').forEach(function(b){b.classList.toggle('active',Number(b.dataset.listRenewMonths)===n);});
   if(row.cents&&row.months){amount.value=(Math.round(row.cents*n/row.months)/100).toFixed(2);}}
  function open(e){var b=e.relatedTarget;if(!b||!b.dataset.renewId){return;}
   row={to:b.dataset.renewTo,cents:Number(b.dataset.renewCents)||0,months:Number(b.dataset.renewMonthsLast)||0};
   form.action='/admin/accounts/'+b.dataset.renewId+'/renew';
   modal.querySelector('[data-renew-name]').textContent=b.dataset.renewName;
   modal.querySelector('[data-renew-current]').textContent='原到期 '+(b.dataset.renewTo||'').replace('T',' ');
   amount.value=row.cents?(row.cents/100).toFixed(2):'';method.value=b.dataset.renewMethod||'';
   pick([1,3,6,12].indexOf(row.months)>=0?row.months:1);}
  function click(e){var b=e.target.closest('[data-list-renew-months]');if(b){pick(Number(b.dataset.listRenewMonths));}}
  modal.addEventListener('show.bs.modal',open);form.addEventListener('click',click);}
 // 批量续期：仅「每户记同一金额」时启用金额与付款方式
 var rec=document.querySelector('[data-batch-renew-record]');
 if(rec){var fields=document.querySelector('[data-batch-renew-fields]');
  function sync(){fields.disabled=!rec.querySelector('input[value=same]').checked;}
  rec.addEventListener('change',sync);sync();}
 // 编辑时改流量周期类型会立即开始新周期，保存前确认
 var period=document.querySelector('[data-period-original]');
 if(period){period.closest('form').addEventListener('submit',function(e){if(!e.defaultPrevented&&period.value!==period.dataset.periodOriginal&&!confirm('修改流量周期类型将从现在开始新周期，本期已用流量清零（原周期保留在历史中）。继续？')){e.preventDefault();}});}
 // 按所选标签统计可用节点（标签并集），为 0 时保存前二次确认
 var hint=document.querySelector('[data-tag-nodes]');
 if(hint){var form=hint.closest('form');
  function count(){var ids=new Set();form.querySelectorAll('[data-tag-id]').forEach(function(p){if(p.querySelector('input[name=tags]').checked){(p.dataset.nodeIds||'').split(' ').filter(Boolean).forEach(function(id){ids.add(id);});}});return ids.size;}
  function show(){var n=count();hint.textContent=n?'将可用 '+n+' 个节点':'未匹配任何可用节点，客户订阅将为空';hint.classList.toggle('text-danger',!n);}
  form.addEventListener('change',function(e){if(e.target.name==='tags'){show();}});show();
  form.addEventListener('submit',function(e){if(!count()&&!confirm('所选标签未匹配任何可用节点，客户订阅将为空。仍要保存？')){e.preventDefault();}});}
});
