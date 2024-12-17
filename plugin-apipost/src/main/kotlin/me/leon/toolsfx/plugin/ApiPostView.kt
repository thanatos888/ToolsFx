@file:Suppress("UNCHECKED_CAST")

package me.leon.toolsfx.plugin

import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.text.Text
import kotlinx.coroutines.*
import me.leon.*
import me.leon.ext.*
import me.leon.ext.fx.*
import me.leon.toolsfx.plugin.ApiConfig.restoreFromConfig
import me.leon.toolsfx.plugin.net.*
import me.leon.toolsfx.plugin.net.HttpUrlUtil.POST_ACTION_DEFAULT
import me.leon.toolsfx.plugin.net.HttpUrlUtil.POST_ACTION_HEX
import me.leon.toolsfx.plugin.table.EditingCell
import tornadofx.*

private const val MAX_SHOW_LENGTH = 1_000_000

class ApiPostView : PluginFragment("ApiPost") {
    override val version = "v1.9.2"
    override val date: String = "2024-12-17"
    override val author = "Leon406"
    override val description = "ApiPost"

    init {
        println("Plugin Info:$description $version $date $author  ")
    }

    private val controller: ApiPostController by inject()
    private lateinit var tfUrl: TextField
    private lateinit var taReqHeaders: TextArea
    private lateinit var taReqContent: TextArea
    private lateinit var textRspStatus: Text
    private lateinit var taRspHeaders: TextArea
    private lateinit var taRspContent: TextArea
    private lateinit var tfJsonPath: TextField
    private lateinit var table: TableView<HttpParams>
    private var tfRepeatNum: TextField by singleAssign()
    private var tfConcurrent: TextField by singleAssign()
    private var tfDelay: TextField by singleAssign()
    private val prettyProperty = SimpleBooleanProperty(true)
    private val hexProperty = SimpleBooleanProperty(false)
    private val showJsonPath = SimpleBooleanProperty(false)
    private val methods =
        mutableListOf(
            "POST",
            "GET",
            "PUT",
            "PATCH",
            "HEAD",
            "DELETE",
            "OPTIONS",
            "TRACE",
            "CONNECT",
        )
    private val bodyType = BodyType.values().map { it.type }

    private val selectedMethod = SimpleStringProperty(methods.first())
    private val selectedBodyType = SimpleStringProperty(bodyType.first())
    private val showRspHeader = SimpleBooleanProperty(false)
    private val showReqHeader = SimpleBooleanProperty(false)
    private val showReqTable = SimpleBooleanProperty(false)
    private val running = SimpleBooleanProperty(false)
    private val requestParams = FXCollections.observableArrayList(HttpParams())
    private val showTableList = listOf("json", "form-data")
    private val fileKeys = arrayOf("file", "files", "image", "images")

    private val reqHeaders
        get() = controller.parseHeaderString(taReqHeaders.text)

    private val reqTableParams
        get() =
            requestParams
                .filter { it.isEnable && it.key.isNotEmpty() && !it.isFile }
                .associate { it.key to it.value }
                .toMutableMap() as MutableMap<String, Any>

    private val uploadParams
        get() = requestParams.firstOrNull { it.isEnable && it.key.isNotEmpty() && it.isFile }

    private val eventHandler = fileDraggedHandler {
        table.selectionModel.selectedItem.valueProperty.value =
            it.joinToString(";") { it.absolutePath }
    }
    private val curlFileHandler = fileDraggedHandler {
        with(it.first()) {
            println(absolutePath)
            if (length() <= 128 * 1024) {
                if (realExtension() in unsupportedExts) {
                    println("unsupported file extension")
                } else {
                    resetUi(readText())
                }
            } else {
                println("not support file larger than 128KB")
            }
        }
    }
    override val root = vbox {
        restoreFromConfig()
        prefWidth = 800.0
        spacing = 8.0
        paddingAll = 8
        hbox {
            spacing = 8.0
            alignment = Pos.CENTER_LEFT
            combobox(selectedMethod, methods)

            tfUrl =
                textfield("https://httpbin.org/anything") {
                    prefWidth = 400.0
                    promptText = "input your url"
                    onDragEntered = curlFileHandler
                }
            button(graphic = imageview(IMG_IMPORT)) {
                tooltip(messages["pasteFromClipboard"])
                action { resetUi(clipboardText()) }
            }
            button(graphic = imageview(IMG_RUN)) {
                enableWhen(!running)
                action { doRequest() }
            }

            button(graphic = imageview("/img/settings.png")) {
                action { openInternalWindow<SettingsView>() }
            }
            button(graphic = imageview(IMG_COPY)) {
                tooltip(messages["copy"])
                action {
                    Request(
                        tfUrl.text,
                        selectedMethod.get(),
                        reqTableParams,
                        reqHeaders,
                        taReqContent.text,
                    )
                        .apply {
                            isJson = selectedBodyType.get() == BodyType.JSON.type
                            requestParams
                                .firstOrNull { it.isEnable && it.key.isNotEmpty() && it.isFile }
                                ?.let { fileParamName = it.key }
                        }
                        .toCurl()
                        .copy()
                }
            }

            tfRepeatNum = textfield {
                promptText = "number"
                prefWidth = DEFAULT_SPACING_10X
                textFormatter = intTextFormatter
            }
            tfConcurrent = textfield {
                promptText = "concurrent"
                prefWidth = DEFAULT_SPACING_10X
                textFormatter = intTextFormatter
            }
            tfDelay = textfield {
                promptText = "delay"
                prefWidth = DEFAULT_SPACING_10X
                textFormatter = intTextFormatter
            }
        }

        hbox {
            spacing = 8.0
            alignment = Pos.CENTER_LEFT
            label("Request:")
            hbox {
                alignment = Pos.CENTER
                togglegroup {
                    togglebutton("Body") {
                        style = "-fx-base: lightblue;"
                        action {
                            showReqHeader.value = false
                            showReqTable.value = selectedBodyType.get() in showTableList
                        }
                    }
                    togglebutton("Header") {
                        style = "-fx-base: lightblue;"
                        action {
                            showReqHeader.value = true
                            showReqTable.value = false
                        }
                    }
                }
            }

            combobox(selectedBodyType, bodyType)
            selectedBodyType.addListener { _, _, newValue ->
                showReqTable.value = (newValue as String) in showTableList
            }

            button("Pretty") { action { taReqContent.text = taReqContent.text.prettyJson() } }
            button("Ugly") { action { taReqContent.text = taReqContent.text.uglyJson() } }
            button(graphic = imageview(IMG_ADD)) {
                visibleWhen(showReqTable)
                action { requestParams.add(HttpParams()) }
            }
            button(graphic = imageview(IMG_REMOVE)) {
                visibleWhen(showReqTable)
                action { requestParams.remove(table.selectionModel.selectedItem) }
            }
        }
        stackpane {
            spacing = 8.0
            prefHeight = 200.0
            taReqContent = textarea {
                promptText = "request body"
                isWrapText = true
                visibleWhen(!showReqHeader)
            }
            table =
                tableview(requestParams) {
                    visibleWhen(showReqTable)

                    column("isEnable", HttpParams::enableProperty).apply {
                        cellFactory = CheckBoxTableCell.forTableColumn(this)
                        prefWidthProperty().bind(this@tableview.widthProperty().multiply(0.1))
                    }

                    column("key", HttpParams::keyProperty).apply {
                        cellFactory = EditingCell.forTableColumn()
                        prefWidthProperty().bind(this@tableview.widthProperty().multiply(0.3))
                    }

                    column("value", HttpParams::valueProperty).apply {
                        cellFactory = EditingCell.forTableColumn()
                        prefWidthProperty().bind(this@tableview.widthProperty().multiply(0.5))
                        onDragEntered = eventHandler
                    }
                    column("isFile", HttpParams::fileProperty) {
                        cellFactory = CheckBoxTableCell.forTableColumn(this)
                        prefWidthProperty().bind(this@tableview.widthProperty().multiply(0.1))
                    }

                    isEditable = true
                }

            taReqHeaders = textarea {
                promptText = "request headers"
                isWrapText = true
                visibleWhen(showReqHeader)
            }
        }

        hbox {
            alignment = Pos.CENTER_LEFT
            label("Response:")
            spacing = 8.0
            hbox {
                alignment = Pos.CENTER
                togglegroup {
                    togglebutton("Body") {
                        style = "-fx-base: lightblue;"
                        action { showRspHeader.value = false }
                    }
                    togglebutton("Header") {
                        style = "-fx-base: lightblue;"
                        action { showRspHeader.value = true }
                    }
                }
            }

            button(graphic = imageview(IMG_COPY)) {
                tooltip(messages["copy"])
                action { taRspContent.text.copy() }
            }

            checkbox("pretty", prettyProperty)
            checkbox("hex", hexProperty)
            hexProperty.addListener { _, _, newValue ->
                HttpUrlUtil.addPostHandle(
                    if (newValue) {
                        POST_ACTION_HEX
                    } else {
                        POST_ACTION_DEFAULT
                    }
                )
            }
            checkbox("jsonpath", showJsonPath)
            tfJsonPath = textfield {
                visibleWhen(showJsonPath)
                promptText = "json path"
            }
        }
        stackpane {
            alignment = Pos.CENTER_RIGHT
            textRspStatus = text()
        }
        stackpane {
            prefHeight = 300.0
            spacing = 8.0
            taRspHeaders = textarea {
                promptText = "response headers"
                isEditable = false
                isWrapText = true
                visibleWhen(showRspHeader)
            }
            taRspContent = textarea {
                promptText = "response body"
                isEditable = false
                isWrapText = true
                visibleWhen(!showRspHeader)
            }
        }
        title = "ApiPost"
    }

    private fun doRequest() {
        if (tfUrl.text.isEmpty() || !tfUrl.text.startsWith("http") && tfUrl.text.length < 11) {
            primaryStage.showToast("plz input legal url")
            return
        }
        running.value = true
        val count = runCatching { tfRepeatNum.text.toInt() }.getOrDefault(1)
        val concurrent = runCatching { tfConcurrent.text.toInt() }.getOrDefault(1)
        val delayMillis = runCatching { tfDelay.text.toLong() }.getOrDefault(0L)
        if (selectedBodyType.get() == BodyType.FORM_DATA.type) {
            reqHeaders["Content-Type"] = HttpUrlUtil.APPLICATION_URL_ENCODE
        }

        val dispatcher = Dispatchers.IO.limitedParallelism(concurrent)

        runAsync {
            val start = System.currentTimeMillis()
            var success = 0
            val countMap: MutableMap<String, Int> = mutableMapOf()
            fun req() =
                if (selectedMethod.get() == "POST") {
                    val bodyType = bodyTypeMap[selectedBodyType.get()]
                    requireNotNull(bodyType)
                    when (bodyType) {
                        BodyType.JSON,
                        BodyType.FORM_DATA ->
                            uploadParams?.run {
                                controller.uploadFile(
                                    tfUrl.text,
                                    this.value.split(",", ";").map { it.toFile() },
                                    this.key,
                                    reqTableParams,
                                    reqHeaders,
                                )
                            }
                                ?: controller.post(
                                    tfUrl.text,
                                    reqTableParams,
                                    reqHeaders,
                                    bodyType == BodyType.JSON,
                                )
                        BodyType.RAW ->
                            controller.postRaw(tfUrl.text, taReqContent.text, reqHeaders)
                    }
                } else {
                    controller.request(
                        tfUrl.text,
                        selectedMethod.get(),
                        reqTableParams,
                        reqHeaders,
                    )
                }
                    .also {
                        if (it.code == 200) {
                            success++
                            countMap[it.data] = countMap[it.data]?.let { it + 1 } ?: 1
                        }
                    }
            runCatching {
                runBlocking {
                    (1..count)
                        .map {
                            async(dispatcher) {
                                req().also {
                                    if (delayMillis > 0) {
                                        // delay 无法阻塞其他
                                        Thread.sleep(delayMillis)
                                    }
                                }
                            }
                        }
                        .awaitAll()
                        .last()
                }
            }
                .onSuccess {
                    handleSuccess(it)
                    if (count > 1) {
                        ui {
                            primaryStage.showToast(
                                "  time  costs : ${System.currentTimeMillis() - start} ms" +
                                        "\nsuccess/total: $success/$count" +
                                        "\n    detail   :\n${
                                            countMap.map { "\t\tresp len: ${it.key.length}  num: ${it.value}" }
                                                .joinToString(System.lineSeparator())
                                        }",
                                3000,
                            )
                        }
                    }
                }
                .onFailure {
                    textRspStatus.text = it.message
                    taRspHeaders.text = ""
                    taRspContent.text = it.stacktrace()
                    this@ApiPostView.running.value = false
                }
        }
    }

    private fun handleSuccess(resp: Response) {
        textRspStatus.text = resp.statusInfo
        taRspHeaders.text = resp.headerInfo

        if (resp.length > MAX_SHOW_LENGTH || resp.data.length > MAX_SHOW_LENGTH) {
            taRspContent.text = "Data is Too Large! ${resp.length} "
            running.value = false
            return
        }
        val showdata =
            if (showJsonPath.get() && tfJsonPath.text.trim().isNotEmpty()) {
                resp.data.simpleJsonPath(tfJsonPath.text.trim())
            } else {
                resp.data
            }
        taRspContent.text =
            if (prettyProperty.get()) {
                showdata.unicodeMix2String().prettyJson()
            } else {
                showdata
            }

        running.value = false
    }

    private fun resetUi(clipboardText: String) {
        clipboardText.parseCurl().run {
            selectedMethod.value = method
            tfUrl.text = url
            taReqHeaders.text = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            if (params.isNotEmpty()) {
                showReqTable.value = true
                showReqHeader.value = false
                selectedBodyType.value = bodyType[1]

                val tmpParam =
                    params.entries
                        .fold(mutableListOf<HttpParams>()) { acc, mutableEntry ->
                            acc.apply {
                                add(
                                    HttpParams().apply {
                                        keyProperty.value = mutableEntry.key
                                        valueProperty.value = mutableEntry.value.toString()
                                        fileProperty.value =
                                            mutableEntry.key in fileKeys ||
                                                    mutableEntry.value.toString() == "@file"
                                    }
                                )
                            }
                        }
                        .distinct()

                requestParams.clear()
                requestParams.addAll(tmpParam)
            } else {
                taReqContent.text = rawBody
                selectedBodyType.value = BodyType.RAW.type
                showReqTable.value = false
            }
        }
    }
}
