(function(){
 var source=document.querySelector('#user_source');
 if(source){function update(){document.querySelector('[data-new-user]').hidden=source.value!=='new';document.querySelector('[data-existing-user]').hidden=source.value==='new';}source.addEventListener('change',update);update();}
 document.querySelectorAll('form').forEach(function(form){form.addEventListener('invalid',function(e){var pane=e.target.closest('.tab-pane');if(pane&&!pane.classList.contains('active')){var tab=document.querySelector('[data-bs-target="#'+pane.id+'"]');if(tab){tab.click();}}},true);});

 var pw=document.querySelector('#new_password');
 function gen(){var c='abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789',a=new Uint32Array(12);crypto.getRandomValues(a);return Array.from(a,function(n){return c[n%c.length];}).join('');}
 if(pw){if(!pw.value){pw.value=gen();}var g=document.querySelector('[data-gen-password]');g.addEventListener('click',function(){pw.value=gen();});}
 var to=document.querySelector('#renew_to_date');
 function pad(n){return String(n).padStart(2,'0');}
 function fmt(d){return d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+'T'+pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds());}
 document.querySelectorAll('[data-renew-months]').forEach(function(b){b.addEventListener('click',function(){
  var base=new Date(to.dataset.base||''),now=new Date();if(isNaN(base)||base<now){base=now;}
  var day=base.getDate();base.setDate(1);base.setMonth(base.getMonth()+Number(b.dataset.renewMonths));
  base.setDate(Math.min(day,new Date(base.getFullYear(),base.getMonth()+1,0).getDate()));to.value=fmt(base);});});
})();
