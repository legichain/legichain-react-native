package com.legichain.kyc

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.sin

/** Vector camera guides and a phone/document antenna animation. */
internal class GuideView(context: Context): View(context) {
    var mode = "document"
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    fun rect(): RectF {
        val aspect=if(mode=="face") .84f else 1.586f
        val w=minOf(width*.88f,height*.88f*aspect);val h=w/aspect
        return RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pen.strokeWidth=3*resources.displayMetrics.density
        pen.color=Color.rgb(116,224,197);pen.style=Paint.Style.STROKE
        if(mode=="nfc") {
            canvas.save();canvas.translate(width/2f,height/2f)
            val scale=minOf(resources.displayMetrics.density,width/270f,height/340f);canvas.scale(scale,scale)
            pen.strokeWidth=2f
            val cx=0f;val cy=0f
            canvas.drawRoundRect(cx-75,cy-145,cx+75,cy+145,24f,24f,pen)
            val offset=sin(android.os.SystemClock.uptimeMillis()/850.0).toFloat()*32
            canvas.drawRoundRect(cx-105+offset,cy-85,cx+40+offset,cy+5,12f,12f,pen)
            for(i in 1..3) canvas.drawArc(cx-25-i*17f,cy-100-i*17f,cx+25+i*17f,cy-50+i*17f,215f,110f,false,pen)
            canvas.restore();postInvalidateDelayed(32)
        } else {
            val bounds=rect()
            if(mode=="face") canvas.drawOval(bounds,pen) else canvas.drawRoundRect(bounds,18f,18f,pen)
        }
    }
}
