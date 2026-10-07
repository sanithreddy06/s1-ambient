package com.s1ambient

import android.app.Application

class AmbientApp : Application() {
    internal val state: AmbientState by lazy { AmbientState(this) }
}
