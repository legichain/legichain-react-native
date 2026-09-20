package com.legichain.kyc

import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Finds a convex four-corner document; coordinates are in original bitmap pixels. */
internal object DocumentBounds {
    private val loaded by lazy { OpenCVLoader.initLocal() }
    fun detect(bitmap: Bitmap): RectF? {
        if (!loaded) return null
        val rgba=Mat();val small=Mat();val gray=Mat();val edge=Mat();val hierarchy=Mat()
        val contours=mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap,rgba)
            val scale=minOf(1.0,640.0/bitmap.width)
            Imgproc.resize(rgba,small,Size(bitmap.width*scale,bitmap.height*scale))
            Imgproc.cvtColor(small,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray,gray,Size(5.0,5.0),0.0)
            Imgproc.Canny(gray,edge,45.0,140.0)
            Imgproc.findContours(edge,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
            for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }) {
                if (Imgproc.contourArea(contour)<small.total()*.10) continue
                val curve=MatOfPoint2f(*contour.toArray());val polygon=MatOfPoint2f();val integer=MatOfPoint()
                try {
                    Imgproc.approxPolyDP(curve,polygon,Imgproc.arcLength(curve,true)*.025,true)
                    if (polygon.total()!=4L) continue
                    integer.fromArray(*polygon.toArray())
                    if (!Imgproc.isContourConvex(integer)) continue
                    val box=Imgproc.boundingRect(integer)
                    if (box.width.toDouble()/box.height !in 1.2..1.95) continue
                    if (Imgproc.contourArea(contour)/(box.width.toDouble()*box.height)<.75) continue
                    return RectF((box.x/scale).toFloat(),(box.y/scale).toFloat(),((box.x+box.width)/scale).toFloat(),((box.y+box.height)/scale).toFloat())
                } finally { curve.release();polygon.release();integer.release() }
            }
            return null
        } finally { contours.forEach { it.release() };rgba.release();small.release();gray.release();edge.release();hierarchy.release() }
    }
}
