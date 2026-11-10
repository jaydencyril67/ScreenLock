package com.jaydencyril67.screenlock

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * One bundled STIX Two Text family for every text view owned by Phantom.
 * Weight is preserved while the font family is always the local STIX asset.
 */
object PhantomTypography {
    private fun stix(context: Context, weight: Int): Typeface {
        val base = context.resources.getFont(R.font.stix_two_text)
        return Typeface.create(base, weight.coerceIn(400, 700), false)
    }

    fun apply(view: View, context: Context) {
        if (view is TextView) {
            val weight = if (view.typeface?.isBold == true) 700 else 400
            view.typeface = stix(context, weight)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                apply(view.getChildAt(index), context)
            }
        }
    }
}
