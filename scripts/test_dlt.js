// 复刻 web/index.html 的 DLT 解析与展示逻辑，验证追加信息
const http = require('http');

var DLT_VER = [
 {d:'2026-01-31',tc:7,apc:7,r:[[5,2,'一等奖',null],[5,1,'二等奖',null],[5,0,'三等奖',5000],[4,2,'三等奖',5000],[4,1,'四等奖',300],[4,0,'五等奖',150],[3,2,'五等奖',150],[3,1,'六等奖',15],[2,2,'六等奖',15],[3,0,'七等奖',5],[2,1,'七等奖',5],[1,2,'七等奖',5],[0,2,'七等奖',5]]},
 {d:'2019-02-18',tc:9,apc:7,r:[[5,2,'一等奖',null],[5,1,'二等奖',null],[5,0,'三等奖',10000],[4,2,'四等奖',3000],[4,1,'五等奖',300],[4,0,'六等奖',200],[3,2,'六等奖',200],[3,1,'七等奖',100],[2,2,'七等奖',100],[3,0,'八等奖',15],[2,1,'八等奖',15],[1,2,'八等奖',15],[0,2,'八等奖',15],[2,0,'九等奖',5],[1,1,'九等奖',5],[1,0,'九等奖',5],[0,1,'九等奖',5]]},
 {d:'2014-05-05',tc:6,apc:6,r:[[5,2,'一等奖',null],[5,1,'二等奖',null],[5,0,'三等奖',null],[4,2,'三等奖',null],[4,1,'四等奖',null],[4,0,'五等奖',200],[3,2,'五等奖',200],[3,1,'六等奖',100],[2,2,'六等奖',100]]},
 {d:'2009-10-17',tc:8,apc:7,r:[[5,2,'一等奖',null],[5,1,'二等奖',null],[5,0,'三等奖',null],[4,2,'四等奖',3000],[4,1,'五等奖',500],[4,0,'六等奖',200],[3,2,'六等奖',200],[3,1,'七等奖',10],[2,2,'七等奖',10],[3,0,'八等奖',5],[2,1,'八等奖',5],[1,2,'八等奖',5]]},
 {d:'2007-05-28',tc:8,apc:7,r:[[5,2,'一等奖',null],[5,1,'二等奖',null],[5,0,'三等奖',null],[4,2,'四等奖',3000],[4,1,'五等奖',500],[4,0,'六等奖',100],[3,2,'六等奖',100],[3,1,'七等奖',10],[2,2,'七等奖',10],[3,0,'八等奖',5],[2,1,'八等奖',5],[1,2,'八等奖',5]]}
];

var cur = {code:'dlt',name:'大乐透',parseMain:5,parseSec:2,extraCount:2,tierCount:7,vers:DLT_VER};

function parseNumberSafe(raw){
  var s=raw.trim();if(!s)return null;
  if(s.indexOf(',')===-1&&s.indexOf('.')===-1)return parseInt(s,10)||0;
  if(s.indexOf(',')===-1)return parseInt(s.split('.')[0],10)||0;
  if(/^[+-]?\d{1,3}(?:,\d{3})*(?:\.\d+)?$/.test(s))return parseInt(s.replace(/,/g,'').split('.')[0],10)||0;
  if(/^[+-]?\d{1,3}(?:\.\d{3})*(?:,\d+)?$/.test(s))return parseInt(s.replace(/\./g,'').split(',')[0],10)||0;
  return null;
}
function verForDate(t,date){
  if(!date||date.length!==10)return null;
  for(var i=0;i<t.vers.length;i++){if(date>=t.vers[i].d)return t.vers[i];}
  return null;
}

function parseDraws(text){
  var lines=text.split(/\r?\n/),out=[];
  for(var i=0;i<lines.length;i++){
    var line=lines[i],tr=line.trim();
    if(!tr)continue;
    var t=tr.split(/\s+/);
    if(!/^\d{5,}$/.test(t[0]))continue;
    if(!/^\d{4}-/.test(t[1]))continue;
    var nums=[];
    var numEnd=2;
    for(var j=2;j<t.length;j++){if(/^\d{1,2}$/.test(t[j])){nums.push(t[j]);numEnd=j+1;}else break;}
    if(nums.length<cur.parseMain+cur.parseSec)continue;
    var main=nums.slice(0,cur.parseMain);
    var sec=cur.parseSec>0?nums.slice(cur.parseMain,cur.parseMain+cur.parseSec):[];
    var extraCount=cur.extraCount||2;
    var sales=null, jackpot=null;
    if(extraCount>=1&&numEnd<t.length)sales=t[numEnd];
    if(extraCount>=2&&numEnd+1<t.length)jackpot=t[numEnd+1];
    var tierStart=numEnd+extraCount;
    var tiers=[];
    for(var k=0;k<15;k++){
      var ci=tierStart+k*2,ai=tierStart+k*2+1;
      if(ci>=t.length||ai>=t.length)break;
      var cRaw=t[ci],aRaw=t[ai];
      if(cRaw==='-'||aRaw==='-'||!cRaw||!aRaw){tiers.push(null);continue;}
      var cnt=parseNumberSafe(cRaw),amt=parseNumberSafe(aRaw);
      if(cnt===null||amt===null){tiers.push(null);continue;}
      tiers.push({count:cnt,amount:amt});
    }
    var validTiers=tiers.filter(function(x){return x!==null;});
    var ver=verForDate(cur,t[1]);
    var tierCount=ver&&ver.tc?ver.tc:(cur.tierCount||7);
    var displayTiers=validTiers.slice(0,tierCount);
    var appendTiers=null;
    if(cur.code==='dlt'&&ver&&ver.apc){
      var ratio=(ver.d>='2019-02-18')?0.8:0.6;
      function baseAmtAt(i){var x=displayTiers[i];return (x&&x.amount>0)?x.amount:0;}
      var appendData=[];
      for(var ak=0;ak<4;ak++){
        var aci=29+ak*2,aai=30+ak*2;
        if(aci>=t.length||aai>=t.length){appendData.push(null);continue;}
        var acRaw=t[aci],aaRaw=t[aai];
        if(acRaw==='-'||aaRaw==='-'||!acRaw||!aaRaw){appendData.push(null);continue;}
        var acnt=parseNumberSafe(acRaw),aamt=parseNumberSafe(aaRaw);
        if(acnt===null||aamt===null){appendData.push(null);continue;}
        appendData.push({count:acnt,amount:aamt});
      }
      var a5Raw=t[37];
      var a5cnt=(a5Raw==='-'||!a5Raw)?0:(parseNumberSafe(a5Raw)||0);
      appendData.push({count:a5cnt,amount:Math.round(baseAmtAt(4)*ratio)});
      appendData.push({count:0,amount:Math.round(baseAmtAt(5)*ratio)});
      appendData.push({count:0,amount:Math.round(baseAmtAt(6)*ratio)});
      appendTiers=appendData.slice(0,ver.apc);
    }
    out.push({issue:t[0],date:t[1],tiers:displayTiers,allTiers:tiers,appendTiers:appendTiers,raw:t});
  }
  return out;
}

function prForVersion(ver){
  var rules=ver.r, order=[], map={};
  rules.forEach(function(r){
    if(!map[r[2]]){map[r[2]]={name:r[2],conds:[],amt:r[3]};order.push(r[2]);}
    map[r[2]].conds.push([r[0],r[1]]);
  });
  return order.map(function(n){return [map[n].name,map[n].conds,map[n].amt];});
}

function fetchData(url){
  return new Promise((res,rej)=>{
    http.get(url,(r)=>{let b='';r.setEncoding('utf8');r.on('data',c=>b+=c);r.on('end',()=>res(b));}).on('error',rej);
  });
}

(async function(){
  const fs = require('fs');
  const text = fs.readFileSync('/workspace/scripts/_dlt_data.txt','utf8');
  const draws = parseDraws(text);
  for(let d of draws.slice(0,3)){
    const ver = verForDate(cur, d.date);
    const rules = prForVersion(ver);
    console.log('\n===== 第'+d.issue+'期 '+d.date+' 字段数='+d.raw.length+' tc='+ver.tc+' apc='+ver.apc+' =====');
    console.log('基本投注 displayTiers('+d.tiers.length+'):');
    d.tiers.forEach((x,i)=>console.log('  ['+i+']',JSON.stringify(x)));
    console.log('allTiers('+d.allTiers.length+'):');
    d.allTiers.forEach((x,i)=>console.log('  ['+i+']',JSON.stringify(x)));
    console.log('appendTiers('+(d.appendTiers?d.appendTiers.length:0)+'):');
    (d.appendTiers||[]).forEach((x,i)=>console.log('  ['+i+']',JSON.stringify(x)));
    var all=d.allTiers||d.tiers||[];
    var tierByName={};
    for(var i=0;i<Math.min(rules.length,all.length);i++){
      if(!tierByName[rules[i][0]])tierByName[rules[i][0]]=all[i];
    }
    console.log('tierByName 映射 (rules.length='+rules.length+'):');
    rules.forEach((p,i)=>{console.log('  '+p[0]+' -> allTiers['+i+']='+JSON.stringify(all[i]));});
  }
})();