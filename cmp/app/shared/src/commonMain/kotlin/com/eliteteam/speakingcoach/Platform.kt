package com.eliteteam.speakingcoach

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform