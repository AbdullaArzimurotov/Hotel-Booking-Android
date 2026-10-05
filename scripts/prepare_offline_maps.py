#!/usr/bin/env python3
"""Подготовка шести небольших OSM-фрагментов, не скачивание tiles.
Запускается разработчиком, не телефоном. Источник OSM API /map, ODbL attribution.
Курс APK поставляется с готовыми .map; при повторном запуске исходник не загружается заново.
"""
from pathlib import Path
import os
import ssl
import subprocess
import urllib.request
import hashlib
import json
import gzip
import dev

ROOT=Path(__file__).resolve().parents[1]
CENTERS={"tashkent":(41.3111,69.2797),"samarkand":(39.6547,66.9750),"moscow":(55.7512,37.6184),"petersburg":(59.9398,30.3146),"istanbul":(41.0082,28.9784),"antalya":(36.8841,30.7056)}

def main():
    source=ROOT/".local/map_sources"
    target=ROOT/"android/app/src/main/assets/maps"
    source.mkdir(parents=True,exist_ok=True);target.mkdir(parents=True,exist_ok=True)
    environment=dict(os.environ)
    if "JAVA_HOME" not in environment:
        environment["JAVA_HOME"]=str(dev.java_home())
    records=[]
    for city,(lat,lon) in CENTERS.items():
        xml=source/(city+".osm");out=target/(city+".map")
        dy,dx=(.0045,.006) if city in ("moscow","petersburg","istanbul") else (.012,.016)
        bbox=f"{lon-dx},{lat-dy},{lon+dx},{lat+dy}"
        url="https://api.openstreetmap.org/api/0.6/map?bbox="+bbox
        archived=ROOT/"docs/maps-source"/(city+".osm.gz")
        if not xml.exists() and archived.exists():
            xml.write_bytes(gzip.decompress(archived.read_bytes()))
        if not xml.exists():
            request=urllib.request.Request(url,headers={"User-Agent":"HotelCoursework/0.9 (https://github.com/AbdullaArzimurotov/Hotel-Booking-Android)"})
            certificate="/etc/ssl/cert.pem" if Path("/etc/ssl/cert.pem").exists() else None
            with urllib.request.urlopen(request,context=ssl.create_default_context(cafile=certificate),timeout=180) as response:
                data=response.read(32*1024*1024)
            if not data.startswith(b"<?xml") and b"<osm" not in data[:500]:
                raise RuntimeError("OSM XML expected")
            xml.write_bytes(data)
        if not out.exists():
            args=f'--read-xml "file={xml}" --mapfile-writer "file={out}" bbox={lat-dy},{lon-dx},{lat+dy},{lon+dx} map-start-position={lat},{lon} preferred-languages=ru,en skip-invalid-relations=true'
            command=([os.environ.get("COMSPEC","cmd.exe"),"/d","/c",str(ROOT/"android/gradlew.bat")] if dev.WINDOWS else [str(ROOT/"android/gradlew")])
            subprocess.run(command+["-p",str(ROOT/"scripts/maps-tool"),"run","--args="+args,"--console=plain"],env=environment,check=True)
        records.append({"city":city,"bbox":[lat-dy,lon-dx,lat+dy,lon+dx],"source":url,"sha256":hashlib.sha256(out.read_bytes()).hexdigest(),"bytes":out.stat().st_size,"license":"ODbL 1.0, © OpenStreetMap contributors"})
        sources=ROOT/"docs/maps-source";sources.mkdir(parents=True,exist_ok=True)
        with gzip.GzipFile(filename=str(sources/(city+".osm.gz")),mode="wb",mtime=0) as stream:
            stream.write(xml.read_bytes())
        print(city,out.stat().st_size,flush=True)
    (target/"manifest.json").write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding="utf-8")

if __name__=="__main__":main()
