package com.haky.edge

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform