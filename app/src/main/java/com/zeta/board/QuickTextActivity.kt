package com.zeta.board

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import org.json.JSONObject

class QuickTextActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private val bg = Color.rgb(12, 13, 23); private val card = Color.rgb(25, 27, 43); private val purple = Color.rgb(163, 91, 255); private val cyan = Color.rgb(66, 220, 238)
    private val match = ViewGroup.LayoutParams.MATCH_PARENT; private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); QuickTextRepo.init(this); build() }
    private fun shape(color: Int, radius: Int = 18): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply { text=s; textSize=size; setTextColor(color); if (bold) typeface=Typeface.DEFAULT_BOLD }
    private fun build() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(12)); setBackgroundColor(bg) }
        val top = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; orientation=LinearLayout.HORIZONTAL }
        val heading = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0,wrap,1f) }
        heading.addView(text("ZETA QUICK TEXT",22f,cyan,true)); heading.addView(text("Your messages. One tap away.",12f,0xFF9CA3B8.toInt()))
        top.addView(heading); top.addView(action("＋ Add",purple) { edit() }); root.addView(top)
        search = EditText(this).apply { hint="Search shortcuts, messages..."; setHintTextColor(0xFF85899E.toInt()); setTextColor(Color.WHITE); textSize=14f; singleLine=true; background=shape(card,14); setPadding(dp(14),0,dp(14),0) }
        root.addView(search, LinearLayout.LayoutParams(match,dp(48)).apply { topMargin=dp(18); bottomMargin=dp(12) })
        search.addTextChangedListener(object: android.text.TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}; override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){ render() }; override fun afterTextChanged(s:android.text.Editable?){} })
        val tools = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        tools.addView(action("Export JSON",card) { export() }, LinearLayout.LayoutParams(0,dp(42),1f)); tools.addView(space(8)); tools.addView(action("Import JSON",card) { import() }, LinearLayout.LayoutParams(0,dp(42),1f)); root.addView(tools)
        val sc = ScrollView(this); list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; sc.addView(list); root.addView(sc,LinearLayout.LayoutParams(match,0,1f).apply { topMargin=dp(14) })
        setContentView(root); render()
    }
    private fun space(w:Int)=View(this).apply { layoutParams=LinearLayout.LayoutParams(dp(w),1) }
    private fun action(label:String,color:Int,onClick:()->Unit)=TextView(this).apply { text=label; textSize=13f; typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER; setTextColor(Color.WHITE); background=shape(color,14); setPadding(dp(12),dp(8),dp(12),dp(8)); setOnClickListener{onClick()} }
    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews(); val items=QuickTextRepo.all(search.text.toString())
        if(items.isEmpty()){ val empty=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(20),dp(50),dp(20),dp(50)) }; empty.addView(text("✦",36f,purple,true)); empty.addView(text(if(search.text.isNullOrEmpty()) "No quick texts yet" else "No matching shortcuts",18f,Color.WHITE,true)); empty.addView(text("Create a shortcut and insert it instantly from your keyboard.",13f,0xFF9CA3B8.toInt())); list.addView(empty); return }
        items.forEach { item ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(card,16); setPadding(dp(14),dp(12),dp(14),dp(12)) }
            val line=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
            val name=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0,wrap,1f) }
            name.addView(text(".${item.shortcut}",16f,cyan,true)); name.addView(text(item.category.uppercase(),10f,0xFF9B83D5.toInt(),true)); line.addView(name)
            line.addView(action(if(item.favorite) "★" else "☆",Color.rgb(46,40,70)){QuickTextRepo.toggleFavorite(item.id,!item.favorite);render()})
            line.addView(space(6)); line.addView(action("Edit",Color.rgb(48,52,75)){edit(item)})
            line.addView(space(6)); line.addView(action("×",Color.rgb(85,39,58)){AlertDialog.Builder(this).setMessage("Delete .${item.shortcut}?").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->QuickTextRepo.delete(item.id);render()}.show()})
            row.addView(line); val preview=text(item.message,13f,0xFFD0D2DF.toInt()); preview.maxLines=3; row.addView(preview,LinearLayout.LayoutParams(match,wrap).apply{topMargin=dp(9)})
            list.addView(row,LinearLayout.LayoutParams(match,wrap).apply{bottomMargin=dp(10)})
        }
    }
    private fun edit(item: QuickText? = null) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),0) }
        val key=EditText(this).apply { hint="Shortcut name (e.g. wa)"; setText(item?.shortcut ?: ""); setTextColor(Color.WHITE); setHintTextColor(0xFF999999.toInt()) }
        val cat=EditText(this).apply { hint="Category (e.g. WhatsApp)"; setText(item?.category ?: "General"); setTextColor(Color.WHITE); setHintTextColor(0xFF999999.toInt()) }
        val msg=EditText(this).apply { hint="Full message..."; setText(item?.message ?: ""); minLines=4; maxLines=10; gravity=Gravity.TOP; setTextColor(Color.WHITE); setHintTextColor(0xFF999999.toInt()) }
        val fav=CheckBox(this).apply { text="Add to favorites"; setTextColor(Color.WHITE); isChecked=item?.favorite ?: false }
        listOf(key,cat,msg,fav).forEach { box.addView(it,LinearLayout.LayoutParams(match,wrap).apply{bottomMargin=dp(10)}) }
        val dialog=AlertDialog.Builder(this).setTitle(if(item==null) "New Quick Text" else "Edit Quick Text").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener { val ok=QuickTextRepo.save(item?.id ?: 0,key.text.toString(),msg.text.toString(),cat.text.toString(),fav.isChecked); if(!ok){key.error="Name must be unique; message cannot be empty"}else{dialog.dismiss();render()} } }
        dialog.show()
    }
    private fun export(){ val i=Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,"zeta-quick-text.json"); startActivityForResult(i,41) }
    private fun import(){ val i=Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,42) }
    @Deprecated("Deprecated in Android API") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){ super.onActivityResult(requestCode,resultCode,data); if(resultCode!=RESULT_OK)return; val uri=data?.data?:return; try { if(requestCode==41) contentResolver.openOutputStream(uri)?.use{it.write(QuickTextRepo.exportJson().toByteArray(Charsets.UTF_8))} else if(requestCode==42){ val s=contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:""; val n=QuickTextRepo.importJson(s); Toast.makeText(this,"Imported $n shortcuts",Toast.LENGTH_LONG).show() }; render() }catch(e:Exception){Toast.makeText(this,"Could not process JSON file",Toast.LENGTH_LONG).show()} }
}
