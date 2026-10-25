(function(){
 var m=document.querySelector('#renew-modal');if(!m)return;
 var months=m.querySelector('#renew-months'),date=m.querySelector('#renew-date'),amount=m.querySelector('#renew-amount'),hint=m.querySelector('[data-renew-hint]');
 var base,price,per;
 function pad(n){return String(n).padStart(2,'0');}
 function parse(s){var p=s.split('-');return new Date(+p[0],+p[1]-1,+p[2]);}
 function fmt(d){return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate());}
 function add(d,n){var last=new Date(d.getFullYear(),d.getMonth()+n+1,0);return new Date(last.getFullYear(),last.getMonth(),Math.min(d.getDate(),last.getDate()));}
 function calc(){
  var n=Number(months.value);if(!n)return;
  date.value=fmt(add(base,n));
  // 周期价按月数折算，分四舍五入后显示为元
  if(price){amount.value=((Math.floor((price*n+per/2)/per))/100).toFixed(2);}
 }
 document.querySelectorAll('[data-renew]').forEach(function(b){b.addEventListener('click',function(){
  var d=b.dataset,today=new Date();today=new Date(today.getFullYear(),today.getMonth(),today.getDate());
  base=d.expire&&parse(d.expire)>today?parse(d.expire):today;price=d.price?Number(d.price):0;per=Number(d.priceMonths)||12;
  m.querySelector('[name=table]').value=d.table;m.querySelector('[name=id]').value=d.id;
  m.querySelector('[data-renew-name]').textContent=d.name;m.querySelector('[data-renew-old]').textContent=d.expire||'未设置';
  hint.textContent=price?'默认按'+(per===1?'月':'年')+'价 '+(price/100).toFixed(2)+' 元折算，可修改':'未设置价格，请手动填写';
  amount.value='';months.value=per===1?'1':'12';calc();
 });});
 months.addEventListener('change',calc);
 date.addEventListener('input',function(){months.value='';});
})();
