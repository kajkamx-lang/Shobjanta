package com.shobjanta.app

import android.content.Context
import android.graphics.Bitmap
import java.io.File

class Doc(val dir: File, val name: String, val folder: String, val pages: List<File>)

object Store {
    lateinit var root: File
    fun init(c: Context) { root = File(c.filesDir, "docs").apply { mkdirs() } }
    private fun txt(d: File, k: String, def: String) = File(d, k).let { if (it.exists()) it.readText() else def }
    private fun pgs(d: File) = d.listFiles { f -> f.extension == "jpg" }.orEmpty().sortedBy { it.name }
    fun all(): List<Doc> = root.listFiles().orEmpty().filter { it.isDirectory }.sortedByDescending { it.name }
        .map { Doc(it, txt(it, "name", "Scan"), txt(it, "folder", ""), pgs(it)) }
    fun create(name: String, folder: String): File = File(root, System.currentTimeMillis().toString()).also {
        it.mkdirs(); meta(it, "name", name); meta(it, "folder", folder)
    }
    fun meta(d: File, k: String, v: String) = File(d, k).writeText(v)
    fun addPage(d: File, b: Bitmap) {
        File(d, "p" + System.nanoTime() + ".jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }
    fun removePage(p: File) { val d = p.parentFile!!; p.delete(); if (pgs(d).isEmpty()) d.deleteRecursively() }
    fun delete(d: File) { d.deleteRecursively() }
}
