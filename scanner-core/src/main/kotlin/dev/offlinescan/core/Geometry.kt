package dev.offlinescan.core

import kotlin.math.abs
import kotlin.math.hypot

object Geometry {
    fun area(q: Quad): Double = abs(q.points.indices.sumOf { i -> val a=q.points[i]; val b=q.points[(i+1)%4]; a.x*b.y-b.x*a.y })/2
    fun valid(q: Quad, minArea: Double = 0.005): Boolean {
        val p=q.points
        if (p.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return false
        val cross=p.indices.map { i -> val a=p[i]; val b=p[(i+1)%4]; val c=p[(i+2)%4]; (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x) }
        return cross.all { it > 1e-8 } && area(q)>=minArea
    }
    fun requireValid(q: Quad) { if (!valid(q)) throw ScanException(ScanError(ErrorCode.INVALID_GEOMETRY,"Corners must form a clockwise convex region inside the image")) }
    fun rotate(p: Point, quarterTurns: Int): Point = when ((quarterTurns%4+4)%4) { 1 -> Point(1-p.y,p.x); 2 -> Point(1-p.x,1-p.y); 3 -> Point(p.y,1-p.x); else -> p }
    fun distance(a: Quad,b: Quad): Double = a.points.zip(b.points).map { (x,y) -> hypot(x.x-y.x,x.y-y.y) }.average()
    /** Map normalized image coordinates to a center-cropped preview viewport. */
    fun preview(p: Point, imageWidth: Double, imageHeight: Double, viewWidth: Double, viewHeight: Double): Point {
        require(listOf(imageWidth,imageHeight,viewWidth,viewHeight).all { it>0 && it.isFinite() })
        val scale=maxOf(viewWidth/imageWidth,viewHeight/imageHeight)
        return Point(p.x*imageWidth*scale+(viewWidth-imageWidth*scale)/2,p.y*imageHeight*scale+(viewHeight-imageHeight*scale)/2)
    }
    fun fromPreview(p: Point, imageWidth: Double,imageHeight: Double,viewWidth: Double,viewHeight: Double): Point {
        val origin=preview(Point(0.0,0.0),imageWidth,imageHeight,viewWidth,viewHeight)
        val end=preview(Point(1.0,1.0),imageWidth,imageHeight,viewWidth,viewHeight)
        return Point((p.x-origin.x)/(end.x-origin.x),(p.y-origin.y)/(end.y-origin.y))
    }
}
