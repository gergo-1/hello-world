(() => {
  const $ = (id) => document.getElementById(id);
  const els = {
    gpsPill:$('gpsPill'), gpsText:$('gpsText'), startBtn:$('startBtn'), etaBig:$('etaBig'), predictionText:$('predictionText'),
    speedVal:$('speedVal'), headingVal:$('headingVal'), headingText:$('headingText'), nearestVal:$('nearestVal'), edgeVal:$('edgeVal'),
    leadSelect:$('leadSelect'), radiusSlider:$('radiusSlider'), radiusOut:$('radiusOut'), notifyBtn:$('notifyBtn'), notifyHint:$('notifyHint'),
    wakeToggle:$('wakeToggle'), operatorSelect:$('operatorSelect'), toast:$('toast')
  };

  const map = L.map('map', { zoomControl:true }).setView([48.2, 16.0], 5);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom:19, attribution:'&copy; OpenStreetMap contributors'
  }).addTo(map);

  const towerLayer = L.layerGroup().addTo(map);
  const rangeLayer = L.layerGroup().addTo(map);
  const pathLayer = L.layerGroup().addTo(map);
  let youMarker = null;
  let accuracyCircle = null;
  let watchId = null;
  let wakeLock = null;
  let towers = [];
  let lastFetch = null;
  let lastPos = null;
  let lastUsableHeading = null;
  let lastAlertAt = 0;
  let lastAlertEdgeKey = '';
  let fetchInFlight = false;

  const towerIcon = L.divIcon({className:'tower-marker',iconSize:[12,12],iconAnchor:[6,6]});
  const youIcon = L.divIcon({className:'you-marker',iconSize:[18,18],iconAnchor:[9,9]});

  function toast(msg){
    els.toast.textContent = msg; els.toast.classList.add('show');
    clearTimeout(toast.t); toast.t=setTimeout(()=>els.toast.classList.remove('show'),2600);
  }
  const rad=d=>d*Math.PI/180, deg=r=>r*180/Math.PI;
  function hav(a,b){
    const R=6371000, dLat=rad(b.lat-a.lat), dLon=rad(b.lon-a.lon), la1=rad(a.lat), la2=rad(b.lat);
    const h=Math.sin(dLat/2)**2+Math.cos(la1)*Math.cos(la2)*Math.sin(dLon/2)**2;
    return 2*R*Math.asin(Math.sqrt(h));
  }
  function bearing(a,b){
    const y=Math.sin(rad(b.lon-a.lon))*Math.cos(rad(b.lat));
    const x=Math.cos(rad(a.lat))*Math.sin(rad(b.lat))-Math.sin(rad(a.lat))*Math.cos(rad(b.lat))*Math.cos(rad(b.lon-a.lon));
    return (deg(Math.atan2(y,x))+360)%360;
  }
  function destination(p, bearingDeg, meters){
    const R=6371000, br=rad(bearingDeg), d=meters/R, lat1=rad(p.lat), lon1=rad(p.lon);
    const lat2=Math.asin(Math.sin(lat1)*Math.cos(d)+Math.cos(lat1)*Math.sin(d)*Math.cos(br));
    const lon2=lon1+Math.atan2(Math.sin(br)*Math.sin(d)*Math.cos(lat1),Math.cos(d)-Math.sin(lat1)*Math.sin(lat2));
    return {lat:deg(lat2),lon:((deg(lon2)+540)%360)-180};
  }
  function compass(h){return ['N','NE','E','SE','S','SW','W','NW'][Math.round(h/45)%8]}
  function fmtEta(sec){
    if(!Number.isFinite(sec)||sec<0)return '—';
    if(sec<60)return `<1 min`;
    const m=Math.round(sec/60); return `${m} min`;
  }

  function inferRadiusKm(t){
    const fallback = Number(els.radiusSlider.value);
    const tech = String(t.tags?.['technology:mobile_phone']||t.tags?.technology||'').toLowerCase();
    if(/nr3500|nr3600|5g.*3500|5g.*3600/.test(tech)) return Math.min(fallback, 2.0);
    if(/lte2600|2600/.test(tech)) return Math.min(fallback, 3.0);
    if(/lte1800|1800/.test(tech)) return Math.max(3.5, Math.min(fallback, 6.0));
    if(/lte800|800|gsm900|900/.test(tech)) return Math.max(fallback, 10.0);
    return fallback;
  }

  function activeTowers(){
    const op=els.operatorSelect.value;
    if(op==='all') return towers;
    return towers.filter(t=>String(t.tags?.operator||'').toLowerCase().includes(op.toLowerCase()));
  }

  function isCovered(p){
    const list=activeTowers();
    if(!list.length) return false;
    return list.some(t => hav(p,t) <= inferRadiusKm(t)*1000);
  }

  function predictEdge(pos, headingDeg){
    if(!activeTowers().length || headingDeg == null) return null;
    const step=100, max=50000;
    // If currently outside estimated coverage, report immediate risk.
    if(!isCovered(pos)) return {distance:0, point:pos};
    for(let d=step; d<=max; d+=step){
      const p=destination(pos,headingDeg,d);
      if(!isCovered(p)) return {distance:d,point:p};
    }
    return null;
  }

  function updateMap(pos, edge){
    const ll=[pos.lat,pos.lon];
    if(!youMarker){ youMarker=L.marker(ll,{icon:youIcon,zIndexOffset:1000}).addTo(map); map.setView(ll,14); }
    else youMarker.setLatLng(ll);
    if(accuracyCircle) accuracyCircle.remove();
    if(pos.accuracy) accuracyCircle=L.circle(ll,{radius:pos.accuracy,weight:1,opacity:.35,fillOpacity:.06}).addTo(map);
    pathLayer.clearLayers();
    if(edge && edge.point && edge.distance>0){
      L.polyline([ll,[edge.point.lat,edge.point.lon]],{dashArray:'8 9',weight:3,opacity:.9}).addTo(pathLayer);
      L.circleMarker([edge.point.lat,edge.point.lon],{radius:7,weight:2,fillOpacity:.85}).bindTooltip('Estimated coverage edge').addTo(pathLayer);
    }
  }

  function renderTowers(){
    towerLayer.clearLayers(); rangeLayer.clearLayers();
    activeTowers().forEach(t=>{
      const title = [t.tags?.operator,t.tags?.name,t.tags?.['technology:mobile_phone']].filter(Boolean).join(' · ') || 'Mapped mobile mast';
      L.marker([t.lat,t.lon],{icon:towerIcon}).bindTooltip(title).addTo(towerLayer);
      if(activeTowers().length<=35){
        L.circle([t.lat,t.lon],{radius:inferRadiusKm(t)*1000,weight:1,opacity:.10,fillOpacity:.018,interactive:false}).addTo(rangeLayer);
      }
    });
  }

  function normalizeOverpass(el){
    const lat = el.lat ?? el.center?.lat, lon=el.lon ?? el.center?.lon;
    if(typeof lat!=='number'||typeof lon!=='number') return null;
    return {lat,lon,tags:el.tags||{},id:`${el.type}/${el.id}`};
  }

  async function fetchTowers(pos){
    if(fetchInFlight) return;
    fetchInFlight=true;
    els.gpsText.textContent='Loading masts…';
    const radius=25000;
    const q=`[out:json][timeout:20];(nwr(around:${radius},${pos.lat},${pos.lon})["communication:mobile_phone"="yes"];nwr(around:${radius},${pos.lat},${pos.lon})["antenna:type"="mobile_phone"];nwr(around:${radius},${pos.lat},${pos.lon})["telecom"="antenna"]["communication:mobile_phone"="yes"];);out center tags;`;
    const endpoints=['https://overpass-api.de/api/interpreter','https://overpass.kumi.systems/api/interpreter'];
    let error=null;
    for(const ep of endpoints){
      try{
        const r=await fetch(ep,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:'data='+encodeURIComponent(q)});
        if(!r.ok) throw new Error(`HTTP ${r.status}`);
        const json=await r.json();
        const seen=new Set();
        towers=(json.elements||[]).map(normalizeOverpass).filter(Boolean).filter(t=>{
          const key=`${t.lat.toFixed(5)},${t.lon.toFixed(5)}`; if(seen.has(key)) return false; seen.add(key); return true;
        });
        lastFetch={lat:pos.lat,lon:pos.lon,at:Date.now()};
        const previous=els.operatorSelect.value;
        const ops=[...new Set(towers.map(t=>String(t.tags?.operator||'').trim()).filter(Boolean))].sort((a,b)=>a.localeCompare(b));
        els.operatorSelect.innerHTML='<option value="all">All mapped masts</option>'+ops.map(op=>`<option value="${op.replaceAll('&','&amp;').replaceAll('\"','&quot;')}">${op}</option>`).join('');
        if([...els.operatorSelect.options].some(o=>o.value===previous)) els.operatorSelect.value=previous;
        renderTowers();
        toast(towers.length?`Loaded ${towers.length} mapped mobile masts`:'No mapped mobile masts found nearby');
        fetchInFlight=false; return;
      }catch(e){error=e}
    }
    fetchInFlight=false;
    toast(`Tower lookup failed: ${error?.message||'network error'}`);
  }

  async function ensureWakeLock(){
    if(!els.wakeToggle.checked || !('wakeLock' in navigator) || wakeLock) return;
    try{wakeLock=await navigator.wakeLock.request('screen'); wakeLock.addEventListener('release',()=>wakeLock=null);}catch{}
  }
  async function releaseWakeLock(){try{await wakeLock?.release()}catch{} wakeLock=null}

  async function sendAlert(edgeKm, etaSec){
    const now=Date.now(), edgeKey=`${Math.round(edgeKm*10)}`;
    if(now-lastAlertAt<120000 && edgeKey===lastAlertEdgeKey) return;
    lastAlertAt=now;lastAlertEdgeKey=edgeKey;
    const body=`Estimated signal edge in ${fmtEta(etaSec)} (${edgeKm.toFixed(1)} km) if speed and direction stay similar.`;
    try{
      if('serviceWorker' in navigator){
        const reg=await navigator.serviceWorker.ready;
        await reg.showNotification('Signal may drop soon',{body,tag:'signal-scout-edge',renotify:true,icon:'icon.svg',badge:'icon.svg'});
      } else if('Notification' in window && Notification.permission==='granted') new Notification('Signal may drop soon',{body});
      navigator.vibrate?.([160,80,160]);
    }catch{}
    toast(body);
  }

  async function onPosition(g){
    const c=g.coords;
    const pos={lat:c.latitude,lon:c.longitude,accuracy:c.accuracy||0};
    let speed=Number.isFinite(c.speed)?Math.max(0,c.speed):null;
    let heading=Number.isFinite(c.heading)?c.heading:null;
    if(lastPos){
      const dt=(g.timestamp-lastPos.timestamp)/1000;
      const d=hav(lastPos,pos);
      if((speed==null||speed===0)&&dt>0.5&&dt<20) speed=d/dt;
      if((heading==null||Number.isNaN(heading))&&d>7) heading=bearing(lastPos,pos);
    }
    if(heading!=null && speed!=null && speed>1) lastUsableHeading=heading;
    const useHeading=heading ?? lastUsableHeading;
    const kph=speed!=null?speed*3.6:null;

    els.gpsPill.classList.add('live'); els.gpsText.textContent=`GPS ±${Math.round(c.accuracy||0)} m`;
    els.speedVal.textContent=kph!=null?kph.toFixed(kph<10?1:0):'—';
    els.headingVal.textContent=useHeading!=null?`${Math.round(useHeading)}°`:'—';
    els.headingText.textContent=useHeading!=null?compass(useHeading):'direction';

    if(!lastFetch || hav(lastFetch,pos)>5000 || Date.now()-lastFetch.at>10*60*1000) fetchTowers(pos);
    if(towers.length){
      const list=activeTowers();
      const nearest=list.length?Math.min(...list.map(t=>hav(pos,t)))/1000:Infinity;
      els.nearestVal.textContent=Number.isFinite(nearest)?nearest.toFixed(nearest<10?1:0):'—';
    }else els.nearestVal.textContent='—';

    let edge=null;
    if(useHeading!=null && activeTowers().length) edge=predictEdge(pos,useHeading);
    if(edge){
      const km=edge.distance/1000; els.edgeVal.textContent=km.toFixed(km<10?1:0);
      const eta=(speed&&speed>0.55)?edge.distance/speed:Infinity;
      if(edge.distance===0){
        els.etaBig.textContent='Risk now';
        els.predictionText.textContent='Your position is outside the current estimated mast-radius coverage model.';
      } else if(Number.isFinite(eta)){
        els.etaBig.textContent=fmtEta(eta);
        els.predictionText.textContent=`About ${km.toFixed(1)} km ahead if you keep roughly ${kph?.toFixed(0)||'this'} km/h on the same heading.`;
        const lead=Number(els.leadSelect.value)*60;
        if(eta<=lead && ('Notification' in window) && Notification.permission==='granted') sendAlert(km,eta);
      }else{
        els.etaBig.textContent=`${km.toFixed(1)} km`;
        els.predictionText.textContent='Estimated edge ahead. Move faster than walking pace for a time estimate.';
      }
    }else if(towers.length && useHeading!=null){
      els.edgeVal.textContent='>50'; els.etaBig.textContent='>50 km'; els.predictionText.textContent='No estimated coverage edge found within 50 km on the current heading.';
    }else{
      els.edgeVal.textContent='—'; els.etaBig.textContent='—';
      els.predictionText.textContent=towers.length?'Move a little so heading can be estimated.':'Looking for mapped mobile masts nearby…';
    }
    updateMap(pos,edge);
    lastPos={...pos,timestamp:g.timestamp};
    ensureWakeLock();
  }

  function onGeoError(e){
    els.gpsPill.classList.remove('live'); els.gpsText.textContent='GPS error';
    const msg=e.code===1?'Location permission was denied. Enable location access for this site.':e.message;
    toast(msg);
  }

  function startTracking(){
    if(!('geolocation' in navigator)){toast('This browser does not support geolocation.');return}
    if(watchId!=null){
      navigator.geolocation.clearWatch(watchId);watchId=null;releaseWakeLock();els.startBtn.textContent='Start tracking';els.gpsPill.classList.remove('live');els.gpsText.textContent='GPS paused';return;
    }
    watchId=navigator.geolocation.watchPosition(onPosition,onGeoError,{enableHighAccuracy:true,maximumAge:2500,timeout:15000});
    els.startBtn.textContent='Stop tracking'; els.gpsText.textContent='Requesting GPS…';
  }

  async function enableNotifications(){
    if(!('Notification' in window)){toast('Notifications are not supported in this browser.');return}
    const p=await Notification.requestPermission();
    updateNotificationUI();
    toast(p==='granted'?'Notifications enabled':'Notifications were not enabled');
  }
  function updateNotificationUI(){
    const p=('Notification'in window)?Notification.permission:'unsupported';
    els.notifyBtn.textContent=p==='granted'?'Enabled':p==='denied'?'Blocked':'Enable';
    els.notifyBtn.disabled=p==='granted';
    els.notifyHint.textContent=p==='granted'?'Alerts are allowed on this device.':p==='denied'?'Notifications are blocked in browser/site settings.':'Allow alerts so Signal Scout can warn you.';
  }

  els.startBtn.addEventListener('click',startTracking);
  els.notifyBtn.addEventListener('click',enableNotifications);
  els.radiusSlider.addEventListener('input',()=>{els.radiusOut.textContent=`${els.radiusSlider.value} km`;renderTowers(); if(lastPos && lastUsableHeading) onPosition({coords:{latitude:lastPos.lat,longitude:lastPos.lon,accuracy:lastPos.accuracy,speed:null,heading:lastUsableHeading},timestamp:performance.timeOrigin+performance.now()});});
  els.wakeToggle.addEventListener('change',()=>els.wakeToggle.checked?ensureWakeLock():releaseWakeLock());
  els.operatorSelect.addEventListener('change',()=>{renderTowers(); if(lastPos&&lastUsableHeading){const edge=predictEdge(lastPos,lastUsableHeading);updateMap(lastPos,edge)}});
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'&&watchId!=null)ensureWakeLock()});

  if('serviceWorker' in navigator) navigator.serviceWorker.register('./sw.js').catch(()=>{});
  updateNotificationUI();
})();
