package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

@Composable
fun RotatedImage(
    uri: Uri,
    rotation: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val w = maxWidth
        val h = maxHeight
        val isSwapped = (rotation % 180 != 0)
        val imgWidth = if (isSwapped) h else w
        val imgHeight = if (isSwapped) w else h

        Image(
            painter = rememberAsyncImagePainter(uri),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier
                .requiredSize(width = imgWidth, height = imgHeight)
                .rotate(rotation.toFloat())
        )
    }
}

// Premium Presets Setup
data class DimensionPreset(val name: String, val width: Double, val height: Double, val spacing: Double)

val ItemSizePresets = listOf(
    DimensionPreset("Passport Photo (3.5x4.5)", 3.5, 4.5, 0.4),
    DimensionPreset("Stamp / Small (2.5x3)", 2.5, 3.0, 0.3),
    DimensionPreset("Photo Card (10.2x15.3)", 10.2, 15.3, 0.5),
    DimensionPreset("Mini Wallet (5x5)", 5.0, 5.0, 0.5),
    DimensionPreset("Business Card (9x5)", 9.0, 5.0, 0.6)
)

data class LayoutState(
    val images: List<Uri> = emptyList(),
    val imageRotations: Map<String, Int> = emptyMap(),
    val paperSize: String = "A4",
    val customPaperWidthVal: Double = 21.0,
    val customPaperHeightVal: Double = 29.7,
    val customPaperWidthStr: String = "21.0",
    val customPaperHeightStr: String = "29.7",
    
    // Parsed safe numeric values
    val itemWidthVal: Double = 3.5,
    val itemHeightVal: Double = 4.5,
    val marginTopVal: Double = 0.5,
    val marginBottomVal: Double = 0.5,
    val marginLeftVal: Double = 0.5,
    val marginRightVal: Double = 0.5,
    val spacingVal: Double = 0.4,
    val slotsVal: Int = 8,
    val repeatImages: Boolean = true,
    
    // Direct string buffer representation for inputs - supports backspace and intermediate typing state!
    val itemWidthStr: String = "3.5",
    val itemHeightStr: String = "4.5",
    val marginTopStr: String = "0.5",
    val marginBottomStr: String = "0.5",
    val marginLeftStr: String = "0.5",
    val marginRightStr: String = "0.5",
    val spacingStr: String = "0.4",
    val slotsStr: String = "8",
    
    // Layout Grid Option Info
    val gridMode: String = "Auto (Dynamic)", // "Auto (Dynamic)" or "Fixed (Cols x Rows)"
    val fixedCols: Int = 0,
    val fixedRows: Int = 0,
    val showBorder: Boolean = true
)

class PrintViewModel : ViewModel() {
    private val _state = MutableStateFlow(LayoutState())
    val state: StateFlow<LayoutState> = _state

    fun addImage(uri: Uri) {
        _state.value = _state.value.copy(images = _state.value.images + uri)
    }

    fun rotateImage(index: Int) {
        val list = _state.value.images
        if (index in list.indices) {
            val uriStr = list[index].toString()
            val currentRot = _state.value.imageRotations[uriStr] ?: 0
            val newRot = (currentRot + 90) % 360
            val newMap = _state.value.imageRotations.toMutableMap()
            newMap[uriStr] = newRot
            _state.value = _state.value.copy(imageRotations = newMap)
        }
    }

    fun getImageRotation(uri: Uri): Int {
        return _state.value.imageRotations[uri.toString()] ?: 0
    }

    fun removeImage(index: Int) {
        val list = _state.value.images.toMutableList()
        if (index in list.indices) {
            val removedUri = list.removeAt(index)
            val newMap = _state.value.imageRotations.toMutableMap()
            newMap.remove(removedUri.toString())
            _state.value = _state.value.copy(images = list, imageRotations = newMap)
        }
    }

    fun clearImages() {
        _state.value = _state.value.copy(images = emptyList(), imageRotations = emptyMap())
    }

    fun updatePaperSize(size: String) {
        _state.value = _state.value.copy(paperSize = size)
        // If fixed grid layout preset is active, recompute size for correctness
        val s = _state.value
        if (s.gridMode.startsWith("Fixed")) {
            selectGridPreset(s.fixedCols, s.fixedRows)
        }
    }

    fun updateCustomPaperWidth(wStr: String) {
        val parsed = wStr.toDoubleOrNull()
        _state.value = _state.value.copy(
            customPaperWidthStr = wStr,
            customPaperWidthVal = if (parsed != null && parsed >= 1.0) parsed else _state.value.customPaperWidthVal
        )
        refreshGridIfFixed()
    }

    fun updateCustomPaperHeight(hStr: String) {
        val parsed = hStr.toDoubleOrNull()
        _state.value = _state.value.copy(
            customPaperHeightStr = hStr,
            customPaperHeightVal = if (parsed != null && parsed >= 1.0) parsed else _state.value.customPaperHeightVal
        )
        refreshGridIfFixed()
    }

    // Handles safe input parsing for numeric dimensions
    fun updateItemWidth(wStr: String) {
        val parsed = wStr.toDoubleOrNull()
        _state.value = _state.value.copy(
            itemWidthStr = wStr,
            itemWidthVal = if (parsed != null && parsed >= 0) parsed else _state.value.itemWidthVal,
            gridMode = "Auto (Dynamic)" // Switches off fixed mode when they customize dimensions manually
        )
    }

    fun updateItemHeight(hStr: String) {
        val parsed = hStr.toDoubleOrNull()
        _state.value = _state.value.copy(
            itemHeightStr = hStr,
            itemHeightVal = if (parsed != null && parsed >= 0) parsed else _state.value.itemHeightVal,
            gridMode = "Auto (Dynamic)"
        )
    }

    fun updateMarginTop(mStr: String) {
        val parsed = mStr.toDoubleOrNull()
        _state.value = _state.value.copy(
            marginTopStr = mStr,
            marginTopVal = if (parsed != null && parsed >= 0) parsed else _state.value.marginTopVal
        )
        refreshGridIfFixed()
    }

    fun updateMarginBottom(mStr: String) {
        val parsed = mStr.toDoubleOrNull()
        _state.value = _state.value.copy(
            marginBottomStr = mStr,
            marginBottomVal = if (parsed != null && parsed >= 0) parsed else _state.value.marginBottomVal
        )
        refreshGridIfFixed()
    }

    fun updateMarginLeft(mStr: String) {
        val parsed = mStr.toDoubleOrNull()
        _state.value = _state.value.copy(
            marginLeftStr = mStr,
            marginLeftVal = if (parsed != null && parsed >= 0) parsed else _state.value.marginLeftVal
        )
        refreshGridIfFixed()
    }

    fun updateMarginRight(mStr: String) {
        val parsed = mStr.toDoubleOrNull()
        _state.value = _state.value.copy(
            marginRightStr = mStr,
            marginRightVal = if (parsed != null && parsed >= 0) parsed else _state.value.marginRightVal
        )
        refreshGridIfFixed()
    }

    fun updateSpacing(sStr: String) {
        val parsed = sStr.toDoubleOrNull()
        _state.value = _state.value.copy(
            spacingStr = sStr,
            spacingVal = if (parsed != null && parsed >= 0) parsed else _state.value.spacingVal
        )
        refreshGridIfFixed()
    }

    fun updateSlots(sStr: String) {
        val parsed = sStr.toIntOrNull()
        _state.value = _state.value.copy(
            slotsStr = sStr,
            slotsVal = if (parsed != null && parsed >= 1) parsed else if (sStr.isEmpty()) 1 else _state.value.slotsVal
        )
    }

    fun updateRepeat(repeat: Boolean) {
        _state.value = _state.value.copy(repeatImages = repeat)
    }

    fun updateShowBorder(show: Boolean) {
        _state.value = _state.value.copy(showBorder = show)
    }

    private fun refreshGridIfFixed() {
        val s = _state.value
        if (s.gridMode.startsWith("Fixed")) {
            selectGridPreset(s.fixedCols, s.fixedRows)
        }
    }

    fun applyPreset(preset: DimensionPreset) {
        _state.value = _state.value.copy(
            itemWidthVal = preset.width,
            itemWidthStr = preset.width.toString(),
            itemHeightVal = preset.height,
            itemHeightStr = preset.height.toString(),
            spacingVal = preset.spacing,
            spacingStr = preset.spacing.toString(),
            gridMode = "Auto (Dynamic)"
        )
    }

    fun selectGridPreset(cols: Int, rows: Int) {
        val (pw, ph) = getPaperDimensions()
        val s = _state.value
        
        val usableWidth = (pw - s.marginLeftVal - s.marginRightVal).coerceAtLeast(1.0)
        val usableHeight = (ph - s.marginTopVal - s.marginBottomVal).coerceAtLeast(1.0)

        // Calculate item dimension based on fixed template
        val itemW = ((usableWidth - (cols - 1) * s.spacingVal) / cols).coerceAtLeast(0.1)
        val itemH = ((usableHeight - (rows - 1) * s.spacingVal) / rows).coerceAtLeast(0.1)

        val wStr = String.format("%.2f", itemW)
        val hStr = String.format("%.2f", itemH)

        _state.value = _state.value.copy(
            gridMode = "Fixed ($cols x $rows)",
            fixedCols = cols,
            fixedRows = rows,
            slotsVal = cols * rows,
            slotsStr = (cols * rows).toString(),
            itemWidthVal = itemW,
            itemWidthStr = wStr,
            itemHeightVal = itemH,
            itemHeightStr = hStr
        )
    }

    fun getPaperDimensions(): Pair<Double, Double> {
        val s = _state.value
        return when (s.paperSize) {
            "A4" -> 21.0 to 29.7
            "A5" -> 14.8 to 21.0
            "US Letter" -> 21.59 to 27.94
            "Legal" -> 21.59 to 35.56
            "10.2 x 15.3 cm" -> 10.2 to 15.3
            "Custom" -> s.customPaperWidthVal to s.customPaperHeightVal
            else -> 21.0 to 29.7 // A4 default
        }
    }

    fun calculateLayout(): Pair<Int, Int> {
        val s = _state.value
        if (s.gridMode.startsWith("Fixed")) {
            return s.fixedCols to s.fixedRows
        }
        val (pw, ph) = getPaperDimensions()
        val usableWidth = pw - s.marginLeftVal - s.marginRightVal
        val usableHeight = ph - s.marginTopVal - s.marginBottomVal

        if (s.itemWidthVal <= 0 || s.itemHeightVal <= 0) return 0 to 0

        val cols = ((usableWidth + s.spacingVal) / (s.itemWidthVal + s.spacingVal)).toInt().coerceAtLeast(0)
        val rows = ((usableHeight + s.spacingVal) / (s.itemHeightVal + s.spacingVal)).toInt().coerceAtLeast(0)
        return cols to rows
    }

    private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int, userRotation: Int = 0): android.graphics.Bitmap? {
        try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            val decoded = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
            } ?: return null

            var exifRotationDegrees = 0
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val exif = android.media.ExifInterface(inputStream)
                    val orientation = exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                    exifRotationDegrees = when (orientation) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val totalRotation = (exifRotationDegrees + userRotation) % 360

            if (totalRotation != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(totalRotation.toFloat()) }
                val rotated = android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated != decoded) {
                    decoded.recycle()
                }
                return rotated
            }
            return decoded
        } catch (t: Throwable) {
            t.printStackTrace()
            return null
        }
    }

    private fun savePdfToDownloads(context: Context, document: PdfDocument): Boolean {
        val fileName = "PrintLayout_${System.currentTimeMillis()}.pdf"
        val resolver = context.contentResolver
        
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        document.writeTo(outputStream)
                    }
                    true
                } catch (t: Throwable) {
                    t.printStackTrace()
                    try {
                        resolver.delete(uri, null, null)
                    } catch (e: Exception) {}
                    false
                }
            } else {
                false
            }
        } else {
            // Older Android versions
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = java.io.File(downloadsDir, fileName)
                java.io.FileOutputStream(file).use { outputStream ->
                    document.writeTo(outputStream)
                }
                // Scan the file so it shows up in downloads
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("application/pdf"), null)
                true
            } catch (t: Throwable) {
                t.printStackTrace()
                false
            }
        }
    }

    fun generateAndSavePdf(context: Context) {
        val s = _state.value
        val (cols, rows) = calculateLayout()
        
        if (s.images.isEmpty()) {
            Toast.makeText(context, "Please select at least one image first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (cols <= 0 || rows <= 0) {
            Toast.makeText(context, "Item dimensions or margins are too large for this sheet.", Toast.LENGTH_SHORT).show()
            return
        }

        // Calculate target dimensions in pixels for high-quality 300 DPI printing
        val reqWidthPx = (s.itemWidthVal / 2.54 * 300).toInt().coerceAtLeast(100)
        val reqHeightPx = (s.itemHeightVal / 2.54 * 300).toInt().coerceAtLeast(100)

        // Decode select images to downsampled bitmaps safely
        val bitmaps = s.images.mapNotNull { uri ->
            val userRot = s.imageRotations[uri.toString()] ?: 0
            decodeSampledBitmapFromUri(context, uri, reqWidthPx, reqHeightPx, userRot)
        }

        if (bitmaps.isEmpty()) {
            Toast.makeText(context, "Could not decode any images safely.", Toast.LENGTH_SHORT).show()
            return
        }

        val document = PdfDocument()
        try {
            val (pw, ph) = getPaperDimensions()
            val pageWidthPoints = (pw * 28.35).toInt()
            val pageHeightPoints = (ph * 28.35).toInt()
            
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidthPoints, pageHeightPoints, 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            val itemWPoints = (s.itemWidthVal * 28.35).toFloat()
            val itemHPoints = (s.itemHeightVal * 28.35).toFloat()
            val spacingPoints = (s.spacingVal * 28.35).toFloat()

            // Calculate actual total grid dimensions in points to center the grid horizontally within the margins
            val gridWidthPoints = if (cols > 0) (cols * itemWPoints + (cols - 1) * spacingPoints) else 0f

            val leftMarginPoints = (s.marginLeftVal * 28.35).toFloat()
            val rightMarginPoints = (s.marginRightVal * 28.35).toFloat()
            val topMarginPoints = (s.marginTopVal * 28.35).toFloat()

            val usableWidthPoints = (pageWidthPoints - leftMarginPoints - rightMarginPoints).coerceAtLeast(0f)

            val horizontalOffset = if (usableWidthPoints > gridWidthPoints) (usableWidthPoints - gridWidthPoints) / 2f else 0f

            val leftPoints = leftMarginPoints + horizontalOffset
            val topPoints = topMarginPoints

            val borderPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.0f // thin black outline
            }

            val maxAvailable = cols * rows
            val totalToPrint = minOf(s.slotsVal, maxAvailable)

            for (i in 0 until totalToPrint) {
                val bitmap = if (s.repeatImages) {
                    bitmaps[i % bitmaps.size]
                } else {
                    if (i < bitmaps.size) bitmaps[i] else null
                }

                if (bitmap != null) {
                    val r = i / cols
                    val c = i % cols
                    val left = leftPoints + c * (itemWPoints + spacingPoints)
                    val top = topPoints + r * (itemHPoints + spacingPoints)
                    val rect = android.graphics.RectF(left, top, left + itemWPoints, top + itemHPoints)
                    
                    canvas.drawBitmap(bitmap, null, rect, null)
                    if (s.showBorder) {
                        canvas.drawRect(rect, borderPaint)
                    }
                }
            }

            document.finishPage(page)

            val success = savePdfToDownloads(context, document)
            if (success) {
                val sizeText = if (s.paperSize == "Custom") "${s.customPaperWidthVal}x${s.customPaperHeightVal} cm" else s.paperSize
                Toast.makeText(context, "Success! PDF saved with size $sizeText to Downloads folder.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Failed to save PDF to Downloads.", Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) {
            Toast.makeText(context, "Failed to generate PDF: ${t.localizedMessage}", Toast.LENGTH_LONG).show()
            t.printStackTrace()
        } finally {
            try {
                document.close()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            // Always recycle bitmaps to free memory immediately
            bitmaps.forEach {
                try {
                    it.recycle()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }
    }
}

class PdfPrintAdapter(private val file: File) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder("print_layout.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(1)
            .build()
        callback?.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?
    ) {
        var input: FileInputStream? = null
        var output: FileOutputStream? = null
        try {
            input = FileInputStream(file)
            output = FileOutputStream(destination?.fileDescriptor)
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } > 0) {
                output.write(buffer, 0, bytesRead)
            }
            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback?.onWriteFailed(e.toString())
        } finally {
            try {
                input?.close()
                output?.close()
            } catch (e: IOException) {
                // Ignore exception
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PrintLayoutApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintLayoutApp(modifier: Modifier = Modifier, viewModel: PrintViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var paperDropdownExpanded by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.addImage(it) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFFFCF8FF),
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                color = Color.White
            ) {
                Button(
                    onClick = { viewModel.generateAndSavePdf(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp)
                        .testTag("save_pdf_button"),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750a4))
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Save Icon")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate & Save PDF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // App Bar / Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color(0xFF6750a4),
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("⎙", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Print Layout Studio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1C1B1F))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("by Mir Zulkifal", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF6750a4))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("•", style = MaterialTheme.typography.labelSmall, color = Color(0xFF49454F).copy(alpha = 0.6f))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Passport & Custom Prints", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = Color(0xFF49454F))
                    }
                }
            }

            // Tab Controls System
            val tabTitles = listOf("Images", "Presets", "Page & Margins")
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFFF3EDF7),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyLarge) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Active Tab UI Panel
            Surface(
                color = Color(0xFFF3EDF7),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (selectedTab) {
                        0 -> { // Images tab
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Selected Images", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1D1B20))
                                if (state.images.isNotEmpty()) {
                                    TextButton(onClick = { viewModel.clearImages() }) {
                                        Text("Clear All", color = Color(0xFFBA1A1A), fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }

                            // Dynamic Horizontally Scrollable Row of thumbnails
                            if (state.images.isEmpty()) {
                                Surface(
                                    color = Color.White,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .clickable {
                                            launcher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF79747E))
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add Icon", tint = Color(0xFF6750a4))
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text("No Images Chosen. Tap to Select Photo", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF49454F))
                                    }
                                }
                            } else {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(115.dp)
                                ) {
                                    items(state.images.size) { index ->
                                        val uri = state.images[index]
                                        val rotation = viewModel.getImageRotation(uri)

                                        Box(
                                            modifier = Modifier
                                                .size(90.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(1.dp, Color(0xFF79747E), RoundedCornerShape(8.dp))
                                        ) {
                                            RotatedImage(
                                                uri = uri,
                                                rotation = rotation,
                                                contentDescription = "Selected Picture Thumbnail",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            // Rotate click overlay (bottom-start)
                                            Surface(
                                                color = Color(0xFF6750a4).copy(alpha = 0.85f),
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .align(Alignment.BottomStart)
                                                    .clickable { viewModel.rotateImage(index) },
                                                shape = RoundedCornerShape(topEnd = 8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = "Rotate image button",
                                                    tint = Color.White,
                                                    modifier = Modifier.padding(4.dp)
                                                )
                                            }
                                            // Delete click overlay
                                            Surface(
                                                color = Color.Black.copy(alpha = 0.6f),
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .align(Alignment.TopEnd)
                                                    .clickable { viewModel.removeImage(index) },
                                                shape = RoundedCornerShape(bottomStart = 8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete image button",
                                                    tint = Color.White,
                                                    modifier = Modifier.padding(4.dp)
                                                )
                                            }
                                        }
                                    }
                                    
                                    // Append block
                                    item {
                                        Surface(
                                            color = Color.White,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .size(90.dp)
                                                .clickable {
                                                    launcher.launch(
                                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                    )
                                                },
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF79747E))
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(imageVector = Icons.Default.Add, contentDescription = "Add picture button", tint = Color(0xFF6750a4))
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = Color(0xFFCAC4D0))

                            // Controls for slots and fill mode - smooth free editing!
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = state.slotsStr,
                                    onValueChange = { viewModel.updateSlots(it) },
                                    label = { Text("Slots to Print", fontWeight = FontWeight.Bold) },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20)),
                                    modifier = Modifier.weight(1.2f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                                Column(
                                    modifier = Modifier.weight(1.2f),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = state.repeatImages,
                                            onCheckedChange = { viewModel.updateRepeat(it) }
                                        )
                                        Text("Repeat/Cycle", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20))
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = state.showBorder,
                                            onCheckedChange = { viewModel.updateShowBorder(it) }
                                        )
                                        Text("Picture Border", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20))
                                    }
                                }
                            }
                        }

                        1 -> { // Custom sizing & presets Tab
                            // Active mode styling indicator
                            Surface(
                                color = Color(0xFFEADDFF),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Layout Mode: ",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF21005D),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = state.gridMode,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF6750A4),
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }

                            Text("Sheet Grid Templates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1D1B20))
                            // Elegant direct grids requested: "5x6, 6x6 etc."
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val gridOptions = listOf(
                                    "3x4" to (3 to 4),
                                    "4x5" to (4 to 5),
                                    "5x6" to (5 to 6),
                                    "6x6" to (6 to 6)
                                )
                                gridOptions.forEach { (label, pr) ->
                                    val (c, r) = pr
                                    val isSelected = state.gridMode == "Fixed ($c x $r)"
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.selectGridPreset(c, r) },
                                        label = { Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            HorizontalDivider(color = Color(0xFFCAC4D0))

                            Text("Photo Dimension Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1D1B20))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(ItemSizePresets) { p ->
                                    FilterChip(
                                        selected = state.gridMode.startsWith("Auto") && state.itemWidthVal == p.width && state.itemHeightVal == p.height,
                                        onClick = { viewModel.applyPreset(p) },
                                        label = { Text(p.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)) }
                                    )
                                }
                            }

                            HorizontalDivider(color = Color(0xFFCAC4D0))

                            Text("Custom Sizing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1D1B20))
                            
                            // Width & height values - smooth free editing!
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = state.itemWidthStr,
                                    onValueChange = { viewModel.updateItemWidth(it) },
                                    label = { Text("Width (cm)", fontWeight = FontWeight.Bold) },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20)),
                                    modifier = Modifier.weight(1f).testTag("width_input"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = state.itemHeightStr,
                                    onValueChange = { viewModel.updateItemHeight(it) },
                                    label = { Text("Height (cm)", fontWeight = FontWeight.Bold) },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20)),
                                    modifier = Modifier.weight(1f).testTag("height_input"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = state.spacingStr,
                                    onValueChange = { viewModel.updateSpacing(it) },
                                    label = { Text("Gap Space (cm)", fontWeight = FontWeight.Bold) },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20)),
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                            }
                        }

                        2 -> { // Margins & page parameters setup
                            Text("Sheet Margins (cm)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1D1B20))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = state.marginTopStr,
                                    onValueChange = { viewModel.updateMarginTop(it) },
                                    label = { Text("Top", fontWeight = FontWeight.Bold) },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20)),
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = state.marginBottomStr,
                                    onValueChange = { viewModel.updateMarginBottom(it) },
                                    label = { Text("Bottom", fontWeight = FontWeight.Bold) },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20)),
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = state.marginLeftStr,
                                    onValueChange = { viewModel.updateMarginLeft(it) },
                                    label = { Text("Left", fontWeight = FontWeight.Bold) },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20)),
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = state.marginRightStr,
                                    onValueChange = { viewModel.updateMarginRight(it) },
                                    label = { Text("Right", fontWeight = FontWeight.Bold) },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20)),
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                            }

                            HorizontalDivider(color = Color(0xFFCAC4D0))

                            Column {
                                Text("Paper Size", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF49454F))
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Surface(
                                        onClick = { paperDropdownExpanded = true },
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White,
                                        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF49454F))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .height(48.dp)
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(state.paperSize, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                            Text("▼", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = paperDropdownExpanded,
                                        onDismissRequest = { paperDropdownExpanded = false }
                                    ) {
                                        listOf("A4", "A5", "US Letter", "Legal", "10.2 x 15.3 cm", "Custom").forEach { size ->
                                            DropdownMenuItem(
                                                text = { Text(size, fontWeight = FontWeight.Bold) },
                                                onClick = {
                                                    viewModel.updatePaperSize(size)
                                                    paperDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                if (state.paperSize == "Custom") {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = state.customPaperWidthStr,
                                            onValueChange = { viewModel.updateCustomPaperWidth(it) },
                                            label = { Text("Custom Width (cm)", fontWeight = FontWeight.Bold) },
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20)),
                                            modifier = Modifier.weight(1f),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true
                                        )
                                        OutlinedTextField(
                                            value = state.customPaperHeightStr,
                                            onValueChange = { viewModel.updateCustomPaperHeight(it) },
                                            label = { Text("Custom Height (cm)", fontWeight = FontWeight.Bold) },
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20)),
                                            modifier = Modifier.weight(1f),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Proportional layout metadata indicators
            val (cols, rows) = viewModel.calculateLayout()
            val maxOnSheet = cols * rows

            // Real Time Visual Paper Grid Preview
            Surface(
                color = Color(0xFF1D1B20),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "LIVE PREVIEW",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Surface(
                            color = Color(0xFF6750a4),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Grid: ${cols}x${rows} • Max: $maxOnSheet Slots",
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    // Proportional paper sheet box
                    val pwAndPh = viewModel.getPaperDimensions()
                    val paperRatio = (pwAndPh.first / pwAndPh.second).toFloat()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(4.dp),
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(if (paperRatio > 0) paperRatio else 0.7f)
                        ) {
                            if (cols <= 0 || rows <= 0) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Layout doesn't fit paper limits",
                                        color = Color.Red,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            } else {
                                BoxWithConstraints(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    val sheetWidthDp = maxWidth
                                    val sheetHeightDp = maxHeight

                                    val pw = pwAndPh.first.toFloat()
                                    val ph = pwAndPh.second.toFloat()

                                    // Map dimensions from CM to DP proportionally based on the container size
                                    val itemWDp = (state.itemWidthVal.toFloat() / pw) * sheetWidthDp.value
                                    val itemHDp = (state.itemHeightVal.toFloat() / ph) * sheetHeightDp.value
                                    val spacingWDp = (state.spacingVal.toFloat() / pw) * sheetWidthDp.value
                                    val spacingHDp = (state.spacingVal.toFloat() / ph) * sheetHeightDp.value

                                    val leftMarginDp = (state.marginLeftVal.toFloat() / pw) * sheetWidthDp.value
                                    val rightMarginDp = (state.marginRightVal.toFloat() / pw) * sheetWidthDp.value
                                    val topMarginDp = (state.marginTopVal.toFloat() / ph) * sheetHeightDp.value
                                    val bottomMarginDp = (state.marginBottomVal.toFloat() / ph) * sheetHeightDp.value

                                    val usableWDp = (sheetWidthDp.value - leftMarginDp - rightMarginDp).coerceAtLeast(0f)
                                    val usableHDp = (sheetHeightDp.value - topMarginDp - bottomMarginDp).coerceAtLeast(0f)

                                    val gridWDp = if (cols > 0) (cols * itemWDp + (cols - 1) * spacingWDp) else 0f

                                    // Calculate same centering offset as PDF (horizontally)
                                    val hOffsetDp = if (usableWDp > gridWDp) (usableWDp - gridWDp) / 2f else 0f

                                    val leftPoints = leftMarginDp + hOffsetDp
                                    val topPoints = topMarginDp

                                    val totalVisible = minOf(state.slotsVal, maxOnSheet)

                                    // Subtle visual guide for the printable margins boundary
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(
                                                start = leftMarginDp.dp,
                                                end = rightMarginDp.dp,
                                                top = topMarginDp.dp,
                                                bottom = bottomMarginDp.dp
                                            )
                                            .border(0.5.dp, Color.LightGray.copy(alpha = 0.3f))
                                    )

                                    // Place items with exact proportional offsets
                                    for (index in 0 until totalVisible) {
                                        val r = index / cols
                                        val c = index % cols
                                        val x = leftPoints + c * (itemWDp + spacingWDp)
                                        val y = topPoints + r * (itemHDp + spacingHDp)

                                        val itemBorderModifier = if (state.showBorder) {
                                            Modifier.border(0.5.dp, Color.Black)
                                        } else {
                                            Modifier.border(0.2.dp, Color(0xFF6750a4).copy(alpha = 0.3f), RoundedCornerShape(1.dp))
                                        }
                                        val shape = if (state.showBorder) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(1.dp)

                                        Surface(
                                            modifier = Modifier
                                                .offset(x = x.dp, y = y.dp)
                                                .size(width = itemWDp.dp, height = itemHDp.dp)
                                                .then(itemBorderModifier),
                                            color = Color(0xFFF3EDF7),
                                            shape = shape
                                        ) {
                                            if (state.images.isNotEmpty()) {
                                                val finalUri = if (state.repeatImages) {
                                                    state.images[index % state.images.size]
                                                } else {
                                                    if (index < state.images.size) state.images[index] else null
                                                }

                                                if (finalUri != null) {
                                                    val rot = viewModel.getImageRotation(finalUri)
                                                    RotatedImage(
                                                        uri = finalUri,
                                                        rotation = rot,
                                                        contentDescription = "Preview Thumbnail",
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                            } else {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.fillMaxSize()
                                                ) {
                                                    Text("⬚", color = Color(0xFF79747E), style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.images.isNotEmpty() && state.slotsVal > maxOnSheet) {
                        Text(
                            text = "Note: Slots exceed page ceiling ($maxOnSheet). Excess items overflow.",
                            color = Color(0xFFFFB4AB),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // OTA Update Center Card
            val otaManager = remember { OtaUpdateManager(context) }
            val updateState by otaManager.updateState.collectAsState()
            val coroutineScope = rememberCoroutineScope()
            var showUrlEditor by remember { mutableStateOf(false) }
            var urlInput by remember { mutableStateOf(otaManager.updateUrl) }

            // Silently check on launch
            LaunchedEffect(Unit) {
                otaManager.checkForUpdates(forceNotifyUpToDate = false)
            }

            // Render update dialog if update or download is active
            OtaUpdateDialog(
                state = updateState,
                manager = otaManager,
                onDismiss = { otaManager.resetState() }
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(0xFF6750a4).copy(alpha = 0.1f),
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Sync",
                                        tint = Color(0xFF6750a4),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    "OTA Update Center",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF1C1B1F)
                                )
                                Text(
                                    "Current: v${otaManager.getCurrentVersionName()} (Build ${otaManager.getCurrentVersionCode()})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF49454F)
                                )
                            }
                        }

                        // Check status indicator / Action
                        if (updateState is OtaUpdateState.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color(0xFF6750a4),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        otaManager.checkForUpdates(forceNotifyUpToDate = true)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750a4)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Check", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Display friendly status lines based on state
                    when (updateState) {
                        is OtaUpdateState.UpToDate -> {
                            Text(
                                "✓ App is fully up-to-date!",
                                color = Color(0xFF4E9F3D),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        is OtaUpdateState.Error -> {
                            val errMsg = (updateState as OtaUpdateState.Error).message
                            Text(
                                "⚠ $errMsg",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        else -> {}
                    }

                    HorizontalDivider(color = Color(0xFFCAC4D0).copy(alpha = 0.4f))

                    // Controls / Config Section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Production Server Config",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1C1B1F)
                        )
                        TextButton(
                            onClick = { showUrlEditor = !showUrlEditor },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                if (showUrlEditor) "Hide Config" else "Configure URL",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6750a4)
                            )
                        }
                    }

                    // Always display the Active OTA URL clearly in Extra Bold!
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Active Server URL:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6750a4)
                        )
                        SelectionContainer {
                            Text(
                                otaManager.updateUrl,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF1C1B1F),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (showUrlEditor) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = {
                                urlInput = it
                                otaManager.updateUrl = it
                            },
                            label = { Text("OTA JSON Server URL", fontWeight = FontWeight.Bold) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            singleLine = false,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF6750a4),
                                focusedLabelColor = Color(0xFF6750a4)
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tip: Host a tiny JSON file containing latestVersionCode, latestVersionName, and updateUrl on GitHub raw or your domain to update users automatically.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF49454F).copy(alpha = 0.8f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Box(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color(0xFFF3EDF7),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Designed & Developed by Mir Zulkifal",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF6750a4),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
