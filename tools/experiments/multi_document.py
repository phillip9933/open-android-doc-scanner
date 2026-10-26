"""Offline synthetic feasibility benchmark, not a production detector.
Run: python3 tools/experiments/multi_document.py --output evidence/multi-document-discovery.json
Requires NumPy and OpenCV Python; no downloads or private photographs.
"""
import argparse
import json
import platform
import resource
import time
from pathlib import Path
import cv2
import numpy as np

W,H=1200,900

def polygon(cx,cy,w,h,angle=0):
    return cv2.boxPoints(((cx,cy),(w,h),angle)).astype(np.float32)

def area(p): return abs(cv2.contourArea(p))
def overlap(a,b):
    intersection=cv2.intersectConvexConvex(a.astype(np.float32),b.astype(np.float32))[0]
    return intersection/max(1,area(a)+area(b)-intersection)

def proposals(image):
    gray=cv2.cvtColor(image,cv2.COLOR_BGR2GRAY)
    edge=cv2.Canny(cv2.GaussianBlur(gray,(5,5),0),35,110)
    edge=cv2.morphologyEx(edge,cv2.MORPH_CLOSE,np.ones((3,3),np.uint8))
    contours,_=cv2.findContours(edge,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE)
    candidates=[]
    for c in sorted(contours,key=cv2.contourArea,reverse=True)[:120]:
        if not W*H*.015<area(c)<W*H*.96: continue
        p=cv2.approxPolyDP(c,.022*cv2.arcLength(c,True),True).reshape(-1,2)
        if len(p)!=4 or not cv2.isContourConvex(p):continue
        if np.any(p[:,0]<2) or np.any(p[:,0]>W-3) or np.any(p[:,1]<2) or np.any(p[:,1]>H-3):continue
        candidates.append(p.astype(np.float32))
    selected=[]
    for p in candidates:
        if not any(overlap(p,q)>.7 for q in selected):selected.append(p)
    # A provisional row-band order; explicit review remains necessary for irregular layouts.
    selected.sort(key=lambda p:(int(np.mean(p[:,1])//180),float(np.mean(p[:,0]))))
    return selected,len(candidates)-len(selected)

def scene(kind):
    background=210 if kind=='low-contrast' else 45
    image=np.full((H,W,3),background,np.uint8)
    documents=[polygon(300,430,350,600),polygon(870,430,350,600)]
    if kind=='cards':documents=[polygon(300,250,400,250),polygon(870,250,400,250),polygon(300,650,400,250),polygon(870,650,400,250)]
    if kind=='rotated':documents=[polygon(300,430,320,550,-12),polygon(870,430,320,550,14)]
    if kind=='mixed':documents=[polygon(300,430,350,600),polygon(870,430,400,250)]
    if kind=='overlap':documents=[polygon(470,430,350,600),polygon(690,490,350,600)]
    if kind=='touching':documents=[polygon(425,430,350,600),polygon(775,430,350,600)]
    if kind=='clipped':documents=[polygon(40,430,350,600),polygon(870,430,350,600)]
    if kind=='negative':documents=[]
    for p in documents:
        cv2.fillConvexPoly(image,p.astype(np.int32),(225,225,225))
        cx,cy=np.mean(p,axis=0).astype(int)
        for dy in range(-60,61,30):cv2.line(image,(cx-95,cy+dy),(cx+95,cy+dy),(80,80,80),2)
    if kind in ('texture','negative'):
        for x in range(0,W,37):cv2.line(image,(x,0),(x,H),(90,90,90),1)
    if kind=='shadow':
        gradient=np.linspace(.45,1,W)[None,:,None]
        image=(image*gradient).astype(np.uint8)
    if kind=='glare':cv2.ellipse(image,(870,430),(210,110),0,0,360,(255,255,255),-1)
    if kind=='blur':image=cv2.GaussianBlur(image,(17,17),5)
    return image,documents

def run():
    results=[]
    for kind in ('separated','cards','rotated','mixed','overlap','touching','clipped','low-contrast','texture','shadow','glare','blur','negative'):
        image,truth=scene(kind)
        start=time.perf_counter();found,deduplicated=proposals(image);elapsed=(time.perf_counter()-start)*1000
        matched=[];used=set()
        for p in found:
            candidates=[(overlap(p,q),i) for i,q in enumerate(truth) if i not in used]
            score,index=max(candidates,default=(0,-1))
            if score>=.8:used.add(index);matched.append(index)
        clipped=[i for i,p in enumerate(truth) if np.any(p[:,0]<0) or np.any(p[:,0]>=W) or np.any(p[:,1]<0) or np.any(p[:,1]>=H)]
        # Diagnostic errors use area coverage, independent from the IoU matching threshold.
        coverage=lambda p,q: cv2.intersectConvexConvex(p,q)[0]/max(1,area(q))
        merges=sum(sum(coverage(p,q)>.5 for q in truth)>1 for p in found)
        splits=sum(sum(coverage(q,p)>.5 for p in found)>1 for q in truth)
        expected=sorted(range(len(truth)),key=lambda i:(int(np.mean(truth[i][:,1])//180),float(np.mean(truth[i][:,0]))))
        results.append(dict(case=kind,expected=len(truth),detected=len(found),matched_iou_080=len(used),missed=len(truth)-len(used),false_positives=len(found)-len(used),merged_proposals=merges,split_documents=splits,duplicate_proposals_suppressed=deduplicated,remaining_duplicate_pairs=sum(overlap(a,b)>.7 for i,a in enumerate(found) for b in found[i+1:]),clipped_documents=len(clipped),clipped_matches=sum(i in used for i in clipped),order_correct=matched==expected if len(used)==len(truth) else None,elapsed_ms=round(elapsed,2)))
    return dict(kind='synthetic CPU feasibility only; not Android/real-camera acceptance',opencv=cv2.__version__,python=platform.python_version(),image_size=[W,H],matching='one-to-one polygon IoU >= 0.80',peak_process_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,results=results)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(run(),indent=2)+'\n')
    print(args.output)
