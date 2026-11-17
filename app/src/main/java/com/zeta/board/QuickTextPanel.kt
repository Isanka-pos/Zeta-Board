package com.zeta.board

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

class QuickTextPanel(ctx: Context, private val insert: (QuickText)->Unit, private val manage: ()->Unit, private val close: ()->Unit) : LinearLayout(ctx) {
    private val d=resources.displayMetrics.density; private val list=LinearLayout(ctx); private val search=EditText(ctx)
    private fun dp(v:Int)=(v*d).toInt()
    private fun bg(c:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(14).toFloat()}
    private fun label(s:String,size:Float,color:Int,bold:Boolean=false)=TextView(context).apply{text=s;textSize=size;setTextColor(color);if(bold)typeface=Typeface.DEFAULT_BOLD}
    init {
        orientation=VERTICAL; setPadding(dp(12),dp(10),dp(12),dp(8)); setBackgroundColor(Color.rgb(15,16,28))
        val head=LinearLayout(ctx).apply{gravity=Gravity.CENTER_VERTICAL}
        head.addView(label("ZETA QUICK TEXT",15f,0xFF55DCEB.toInt(),true),LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        val manageBtn=label("Manage",12f,0xFFB58BFF.toInt(),true).apply{setPadding(dp(10),dp(8),dp(10),dp(8));setOnClickListener{manage()}}
        head.addView(manageBtn); val x=label("✕",18f,Color.WHITE,true).apply{setPadding(dp(10),dp(6),dp(4),dp(6));setOnClickListener{close()}};head.addView(x);addView(head)
        search.hint="Search quick texts...";search.setTextColor(Color.WHITE);search.setHintTextColor(0xFF888CA0.toInt());search.textSize=14f;search.singleLine=true;search.background=bg(0xFF25273B.toInt());search.setPadding(dp(12),0,dp(12),0)
        addView(search,LayoutParams(LayoutParams.MATCH_PARENT,dp(42)).apply{topMargin=dp(8);bottomMargin=dp(8)})
        val scroll=ScrollView(ctx);scroll.addView(list);addView(scroll,LayoutParams(LayoutParams.MATCH_PARENT,0,1f))
        search.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){render()};override fun afterTextChanged(s:android.text.Editable?) {}})
    }
    fun open(){QuickTextRepo.init(context);search.setText("");render()}
    private fun render(){list.removeAllViews();val items=QuickTextRepo.all(search.text.toString()); if(items.isEmpty()){list.addView(label("No saved shortcuts yet. Tap Manage to create one.",13f,0xFF9DA2B8.toInt()).apply{setPadding(dp(8),dp(20),dp(8),dp(20))});return}
        val groups = listOf("FAVORITES" to items.filter { it.favorite }, "FREQUENTLY USED" to items.filter { !it.favorite && it.useCount >= 3 }, "RECENT" to items.filter { !it.favorite && it.useCount < 3 })
        groups.forEach { (title, group) -> if (group.isNotEmpty()) {
            list.addView(label(title,10f,0xFF9B83D5.toInt(),true).apply{setPadding(dp(4),dp(7),dp(4),dp(6))})
            group.forEach{item->
                val row=LinearLayout(context).apply{orientation=VERTICAL;background=bg(0xFF202238.toInt());setPadding(dp(12),dp(9),dp(12),dp(9));isClickable=true;isFocusable=true;setOnClickListener{QuickTextRepo.used(item.id);insert(item)}}
                val top=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL};top.addView(label(".${item.shortcut}",14f,0xFF65DDEB.toInt(),true),LayoutParams(0,LayoutParams.WRAP_CONTENT,1f));top.addView(label(item.category,10f,0xFFB58BFF.toInt(),true));row.addView(top)
                val preview=label(item.message.replace('\n',' '),12f,0xFFD0D2E0.toInt());preview.maxLines=1;row.addView(preview);list.addView(row,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(7)})
            }
        } }
    }
}
