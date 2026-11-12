package ink.sysu.materials

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.security.MessageDigest

private const val TOOL="sysu_materials_search"
private const val SITE="https://arxiv.jaison.ink"
private const val REPO="thinktraveller/SYSU_freshman_materials"
private fun enc(s:String)=URLEncoder.encode(s,"UTF-8").replace("+","%20")
private fun JsonObject.s(k:String)=(this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.n(k:String)=(this[k] as? JsonPrimitive)?.longOrNull ?: 0L
private fun canon(s:String):String {
    var t=s.lowercase(Locale.ROOT)
    for((a,b) in listOf("高等数学" to "高数","线性代数" to "线代","概率论与数理统计" to "概统","概率统计" to "概统","军事理论" to "军理","大学英语四级" to "四级","大学英语六级" to "六级")) t=t.replace(a,b)
    return t
}
private fun prettySize(n:Long)=when {n<=0->"大小未知";n<1024*1024->"${n/1024} KB";else->String.format(Locale.ROOT,"%.1f MB",n/1048576.0)}
private data class Item(val id:String,val title:String,val source:String,val url:String,val download:String="",val filename:String="",val size:Long=0,val info:String="",val rank:Int=0) {
    fun json()=buildJsonObject {put("id",id);put("title",title);put("source",source);put("source_url",url);put("download_url",download);put("filename",filename);put("size",size);put("info",info);put("download_state",if(download.isBlank())"login_or_source" else "direct")}
    companion object {fun from(j:JsonObject)=Item(j.s("id"),j.s("title"),j.s("source"),j.s("source_url"),j.s("download_url"),j.s("filename"),j.n("size"),j.s("info"))}
}
private data class SearchResult(val items:List<Item>,val notices:List<String>) {
    fun json()=buildJsonObject {put("items",JsonArray(items.map{it.json()}));put("notices",JsonArray(notices.map{JsonPrimitive(it)}))}
}

class SysuMaterialsPlugin:DeepDeepPlugin {
    private val mutex=Mutex()
    private val downloadStates=mutableStateMapOf<String,String>()
    private val saved=mutableStateMapOf<String,String>()
    private val resultCache=mutableStateMapOf<String,JsonObject>()
    private fun resultKey(args:JsonObject)=MessageDigest.getInstance("SHA-256").digest(args.toString().toByteArray()).joinToString(""){"%02x".format(it)}
    private suspend fun saveResult(host:PluginHost,args:JsonObject,result:SearchResult) {
        val key=resultKey(args);val json=result.json();resultCache[key]=json
        withContext(Dispatchers.IO){val dir=File(host.dataDir,"results");dir.mkdirs();File(dir,"$key.json").writeText(json.toString())}
    }
    override suspend fun availability(host:PluginHost)=PluginAvailability.Available
    override fun contributions(host:PluginHost)=Contributions(tools=listOf(PluginTool(
        name=TOOL,label="检索中大资料",
        description="搜索中山大学学习与新生资料，来源限定 SYSU_freshman_materials 与 arxiv.jaison.ink。返回真实来源链接和可下载资料卡片。适用于课程真题、笔记、复习、四六级、军理、选课等。",
        parameters=Json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string","description":"简短课程或主题关键词，例如 线性代数、高等数学、军事理论、选课；不要放整句问话"},"kind":{"type":"string","enum":["all","past_exam","study_material"],"description":"真题选 past_exam，笔记/复习资料选 study_material"},"source":{"type":"string","enum":["all","github","community"]},"year":{"type":"integer","description":"仅用户明确指定时传入年份"},"limit":{"type":"integer","minimum":1,"maximum":10}},"required":["query"],"additionalProperties":false}""").jsonObject,
        promptSnippet="用户寻找中大课程、新生、生活或考试资料时使用 sysu_materials_search。",
        promptGuidelines=listOf("先调用工具再回答，不编造 URL、资料年份或是否含答案。","query 提取课程/主题；资料类型与年份分别传入 kind/year。","工具卡片已提供查看来源和下载按钮。社区登录限制与来源失败需如实说明。"),
        execute={call->
            val q=call.args.s("query").trim().take(100)
            if(q.isBlank()) PluginToolResult.text("请输入课程或主题关键词。",isError=true)
            else try {
                call.signal?.throwIfCancelled()
                val r=search(host,q,call.args.s("kind"),call.args.s("source"),call.args.n("year").toInt(),call.args.n("limit").toInt().let{if(it==0)8 else it.coerceIn(1,10)})
                saveResult(host,call.args,r)
                call.signal?.throwIfCancelled()
                PluginToolResult.text(buildString {
                    append("找到 ${r.items.size} 条匹配资料。\n")
                    r.items.forEachIndexed{i,x->append("${i+1}. ${x.title} [${x.source}] ${x.info}\n来源：${x.url}\n")}
                    r.notices.forEach{append("提示：$it\n")}
                    append("请使用资料卡片的查看原页、下载或登录后下载按钮；下载操作由用户点击发起。")
                },r.json())
            } catch(e:CancellationException){throw e} catch(e:Exception){PluginToolResult.text("检索失败：${e.message}",isError=true)}
        }
    )))
    override fun ui(host:PluginHost)=object:PluginUiContributions {
        override val bindings=listOf(SlotBinding(SlotIds.toolCard(TOOL),content=SlotContent.Compose { state->
            val card=state as? ToolCardSlotState
            val r=card?.result
            val key=card?.args?.let(::resultKey)
            var restored by remember(key){mutableStateOf<JsonObject?>(null)}
            var refreshing by remember{mutableStateOf(false)}
            var failure by remember{mutableStateOf<String?>(null)}
            val scope=rememberCoroutineScope()
            LaunchedEffect(key){if(key!=null)restored=withContext(Dispatchers.IO){runCatching{Json.parseToJsonElement(File(host.dataDir,"results/$key.json").readText()).jsonObject}.getOrNull()}}
            val details=when(r){is PluginToolResult.Text->r.details;is PluginToolResult.Rich->r.details;else->null} ?: key?.let{resultCache[it]} ?: restored
            if(details!=null) Results(host,SearchResult((details["items"] as? JsonArray).orEmpty().mapNotNull{(it as? JsonObject)?.let(Item::from)},(details["notices"] as? JsonArray).orEmpty().map{it.jsonPrimitive.content}))
            else Column {
                Text(failure ?: if(r?.isError==true) (r as? PluginToolResult.Text)?.text ?: "检索失败" else "展开资料与下载操作")
                if(card!=null) Button(enabled=!refreshing,onClick={scope.launch{refreshing=true;try{val a=card.args;val found=search(host,a.s("query"),a.s("kind"),a.s("source"),a.n("year").toInt(),a.n("limit").toInt().let{if(it==0)8 else it.coerceIn(1,10)});saveResult(host,a,found);restored=found.json()}catch(e:CancellationException){throw e}catch(e:Exception){failure=e.message}finally{refreshing=false}}}){Text(if(refreshing)"正在加载…" else "加载资料卡片")}
                else TextButton(onClick={host.ui().openPage("sysu.materials")}){Text("打开中大资料")}
            }
        }))
        override val pages=listOf(PluginPage("sysu.materials",LocalizedText.Literal("中大资料"),content={Browser(host)}))
    }
    private suspend fun get(host:PluginHost,url:String):JsonObject {
        val r=host.network().request("GET",url,mapOf("Accept" to "application/json","User-Agent" to "SYSU-Materials-DeepDeep/1.0"),timeoutMs=25_000)
        check(r.statusCode in 200..299){"HTTP ${r.statusCode}"}
        return Json.parseToJsonElement(r.body).jsonObject
    }
    private suspend fun github(host:PluginHost,q:String,kind:String,year:Int):Pair<List<Item>,String?> = mutex.withLock {
        val cache=File(host.dataDir,"github-tree.json")
        var note:String?=null
        val tree:JsonObject=if(cache.exists() && System.currentTimeMillis()-cache.lastModified()<6*3600000L) Json.parseToJsonElement(cache.readText()).jsonObject else try {
            val fresh=get(host,"https://api.github.com/repos/$REPO/git/trees/main?recursive=1")
            check(fresh["truncated"]?.jsonPrimitive?.booleanOrNull!=true){"仓库目录不完整"}
            withContext(Dispatchers.IO){host.dataDir.mkdirs();val tmp=File(host.dataDir,"github-tree.tmp");tmp.writeText(fresh.toString());check(tmp.renameTo(cache)){"缓存更新失败"}}
            fresh
        } catch(e:CancellationException){throw e} catch(e:Exception){
            val fallback=cache
            if(!fallback.exists())throw e
            note="GitHub 实时目录暂不可用，已使用缓存目录（${e.message}）。"
            Json.parseToJsonElement(fallback.readText()).jsonObject
        }
        val tokens=canon(q).split(Regex("[\\s,，、]+" )).filter{it.isNotBlank()}
        val commit=tree.s("sha").ifBlank{"main"}
        val items=(tree["tree"] as? JsonArray).orEmpty().mapNotNull { el->
            val j=el.jsonObject;val path=j.s("path");val text=canon(path)
            if(j.s("type")!="blob"||j.n("size")<=0||path.endsWith(".downloading")||path.startsWith("."))return@mapNotNull null
            if(tokens.isEmpty()||tokens.any{!text.contains(it)})return@mapNotNull null
            if(year>0&&!path.contains(year.toString()))return@mapNotNull null
            if(kind=="past_exam"&&!listOf("真题","试卷","期末","考试").any{path.contains(it)})return@mapNotNull null
            val encoded=path.split('/').joinToString("/"){enc(it)}
            Item("gh:"+j.s("sha"),path.substringAfterLast('/'),"GitHub","https://github.com/$REPO/blob/$commit/$encoded","https://raw.githubusercontent.com/$REPO/$commit/$encoded",path.substringAfterLast('/'),j.n("size"),path.substringBeforeLast('/',""),tokens.sumOf{if(canon(path.substringAfterLast('/')).contains(it))3 else 1})
        }.sortedByDescending{it.rank}.distinctBy{it.id}
        items to note
    }
    private suspend fun community(host:PluginHost,q:String,kind:String,year:Int):Pair<List<Item>,List<String>> = coroutineScope {
        val queries=listOf(q,canon(q)).distinct()
        val partial=queries.map { keyword->async {
            listOf("materials","packages").map { type->async {
                try {
                    val u="$SITE/api/$type?search=${enc(keyword)}&page_size=30"+(if(kind in listOf("past_exam","study_material")&&type=="materials")"&category=$kind" else "")+(if(year>0)"&year=$year" else "")
                    val body=get(host,u)
                    val items=(body["items"] as? JsonArray).orEmpty().mapNotNull { e->
                        val j=e.jsonObject;val id=j.s("id");if(id.isBlank())return@mapNotNull null
                        val title=j.s("title").ifBlank{j.s("name")}.ifBlank{j.s("file_name")}
                        val all=title+" "+j.s("description")+" "+j.s("course_name")
                        if(year>0 && j.n("year")!=year.toLong() && !all.contains(year.toString()))return@mapNotNull null
                        if(type=="packages"&&kind=="past_exam"&&!listOf("真题","试卷","期末","考试").any{all.contains(it)})return@mapNotNull null
                        val singular=if(type=="materials")"material" else "package"
                        Item("community:$singular:$id",title.take(220),"中大资料社区","$SITE/$singular/$id",filename=j.s("file_name"),size=j.n("file_size"),info=listOf(j.s("course_name"),j.s("department"),j.s("year").takeIf{it!="null"}.orEmpty(),if(type=="packages")"课程包" else j.s("file_type")).filter{it.isNotBlank()}.joinToString(" · "))
                    }
                    items to null
                } catch(e:CancellationException){throw e} catch(e:Exception){emptyList<Item>() to "社区${if(type=="packages")"课程包" else "资料"}检索暂不可用：${e.message}"}
            }}.awaitAll()
        }}.awaitAll().flatten()
        partial.flatMap{it.first}.distinctBy{it.id} to partial.mapNotNull{it.second}.distinct()
    }
    private suspend fun search(host:PluginHost,q:String,kind:String="all",source:String="all",year:Int=0,limit:Int=8):SearchResult=coroutineScope {
        val gh=async {if(source=="community")emptyList<Item>() to null else try{github(host,q,kind,year)}catch(e:CancellationException){throw e}catch(e:Exception){emptyList<Item>() to "GitHub 检索失败：${e.message}"}}
        val co=async {if(source=="github")emptyList<Item>() to emptyList<String>() else community(host,q,kind,year)}
        val g=gh.await();val c=co.await();val notices=mutableListOf<String>()
        g.second?.let{notices.add(it)};notices.addAll(c.second)
        val merged=mutableListOf<Item>()
        for(i in 0 until maxOf(g.first.size,c.first.size)){c.first.getOrNull(i)?.let{merged.add(it)};g.first.getOrNull(i)?.let{merged.add(it)}}
        val selected=merged.distinctBy{it.url}.take(limit)
        if(selected.any{it.source!="GitHub"})notices.add("社区资料需在原站登录后下载；不会读取或复制浏览器登录信息。")
        if(selected.isEmpty())notices.add("未找到匹配资料，请缩短关键词或尝试课程全称/简称。")
        SearchResult(selected,notices)
    }
    @Composable private fun Browser(host:PluginHost) {
        var q by remember {mutableStateOf("线性代数")};var source by remember{mutableStateOf("all")}
        var result by remember {mutableStateOf<SearchResult?>(null)};var busy by remember{mutableStateOf(false)}
        val scope=rememberCoroutineScope()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("中大资料",style=MaterialTheme.typography.headlineMedium)
            Text("查课程、找真题，也找新生与校园生活资料。",style=MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value=q,onValueChange={q=it.take(100)},label={Text("课程或关键词")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("all" to "全部","github" to "GitHub","community" to "资料社区").forEach{(id,label)->FilterChip(selected=source==id,onClick={source=id},label={Text(label)})}
            }
            Button(onClick={scope.launch{busy=true;try{result=search(host,q.trim(),source=source)}finally{busy=false}}},enabled=!busy&&q.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在查询两个资料源…" else "搜索资料")}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            result?.let{Results(host,it)}
            Text("也可以直接在 deepdeep 聊天中说：帮我找中大线代期末真题。",style=MaterialTheme.typography.bodySmall)
        }
    }
    @Composable private fun Results(host:PluginHost,r:SearchResult) {
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("找到 ${r.items.size} 条资料",style=MaterialTheme.typography.titleMedium)
            r.notices.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            r.items.forEach{item->key(item.id){
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(item.title,style=MaterialTheme.typography.titleSmall)
                        Text("${item.source} · ${prettySize(item.size)}",style=MaterialTheme.typography.labelMedium)
                        if(item.info.isNotBlank())Text(item.info.take(200),style=MaterialTheme.typography.bodySmall)
                        Text(item.url,style=MaterialTheme.typography.bodySmall,maxLines=3)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={host.ui().openUrl(item.url)}){Text("查看原页")}
                            val status=downloadStates[item.id].orEmpty();val active=status.startsWith("下载中")
                            Button(enabled=!active,onClick={if(item.download.isBlank())host.ui().openUrl(item.url) else download(host,item)}){Text(if(item.download.isBlank())"登录后下载" else if(active)"下载中…" else if(saved.containsKey(item.id))"重新下载" else "下载")}
                        }
                        downloadStates[item.id]?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
                        saved[item.id]?.let{path->TextButton(onClick={host.ui().share(ShareRequest(filePath=path))}){Text("打开 / 分享已下载文件")}}
                    }
                }
            }}
        }
    }
    private fun download(host:PluginHost,item:Item) {
        if(downloadStates[item.id]?.startsWith("下载中")==true)return
        if(item.size>512L*1024*1024){host.ui().toast("文件超过 512 MB，请前往原页下载。");return}
        downloadStates[item.id]="下载中…"
        host.scope.launch {
            try {
                val safe=item.filename.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").take(120).ifBlank{"material"}
                val dir=File(host.dataDir,"downloads");dir.mkdirs()
                val target=File(dir,item.id.takeLast(12)+"_"+safe)
                val done=host.network().download(item.download,target.absolutePath,onProgress={p->downloadStates[item.id]="下载中 ${p?.let{"$it%"}?:"…"}"})
                // Git LFS pointers are metadata, not the requested document.
                val pointer=withContext(Dispatchers.IO){if(done.size<1024)target.readText().startsWith("version https://git-lfs.github.com/spec/v1") else false}
                check(!pointer){"此文件使用 Git LFS，请打开原页下载。"}
                saved[item.id]=done.path;downloadStates[item.id]="已下载 · ${prettySize(done.size)}";host.ui().toast("下载完成：${item.title}")
            }catch(e:CancellationException){downloadStates[item.id]="下载已取消";throw e}catch(e:Exception){downloadStates[item.id]="下载失败：${e.message}"}
        }
    }
}
