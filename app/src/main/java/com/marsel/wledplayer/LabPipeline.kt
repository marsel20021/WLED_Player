package com.marsel.wledplayer

import java.util.Collections
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Настройки цвета движка Lab. Всё, что не зависит от сети и Android. */
data class LabParams(
    val gamma: Double = 2.2,
    /** Общая яркость ленты, 0..1. */
    val brightness: Float = 1f,
    /** «Яркость цвета» 0..100: 50 примерно соответствует насыщенности 115% в v2. */
    val vivid: Int = 50,
    /** «Скорость реакции» 0..100. */
    val speed: Int = 50,
    /** «Вес к краю» 0..100: чем больше, тем быстрее убывает влияние пикселей от края кадра вглубь. */
    val edge: Int = 50,
    /** Фоновый свет в темноте: доля максимальной яркости ленты (0 = выключен). */
    val glow: Float = 0.009f,
    /** false: блики приглушаются; true: блик остаётся акцентом, но не растекается на соседей. */
    val accentHighlights: Boolean = false,
    /** Защита от дрожи: 0 выкл, 1 средняя, 2 сильная. */
    val jitter: Int = 1,
    /** Частота вывода на ленту. При 45 и выше включается временной дизеринг. */
    val outputFps: Int = 30
)

/** Геометрия ленты и область захвата (в процентах и клетках сетки). */
data class LabGeometry(
    val top: Int,
    val right: Int,
    val bottom: Int,
    val left: Int,
    val reverse: Boolean,
    val offset: Int,
    val marginX: Int,
    val marginY: Int,
    /** Глубина захвата в % размера картинки; влияние пикселей убывает от края до этой глубины. */
    val depth: Int,
    /** Чёрные полосы сверху/снизу и слева/справа в клетках сетки (0 = нет). */
    val barsV: Int,
    val barsH: Int
)

/**
 * Цветовое ядро движка Lab. Не зависит от Android: на вход идёт сетка пикселей, на выход байты для ленты.
 *
 * Работает в два шага с разной частотой:
 *  - [ingest] вызывается на каждый захваченный кадр (до ~30 раз в секунду): считает цвета зон;
 *  - [step] вызывается на каждый вывод на ленту (до 60 раз в секунду): плавно ведёт цвета к цели
 *    и готовит байты. Так при 60 Гц переходы получаются «шёлковыми», хотя кадры берём реже.
 *
 * Что делает [ingest]:
 *  1. Переводит кадр в линейный свет (честное количество света) и оценивает «пересвеченность» пикселей.
 *  2. Зона каждого диода — не прямоугольник, а набор пикселей с весами: у края кадра вес 1, вглубь он
 *     плавно падает, а вдоль ленты соседние зоны перекрываются (сумма весов вдоль края равна 1).
 *  3. Усредняет цвет зоны в линейном свете. Блики либо приглушаются (мягкое сжатие яркости + меньший
 *     вес почти белых пикселей), либо остаются, но не затекают на соседние диоды.
 *  4. Добавляет тусклый фоновый свет в тёмных зонах (цвет берёт у самой зоны, а если она чёрная, то у сцены).
 *  5. Переводит цвет в Oklab и усиливает насыщенность (оттенок и яркость не меняются).
 *  6. Определяет смену сцены (по числу изменившихся зон и по гистограмме оттенков) и применяет
 *     мёртвую зону против дрожи.
 *
 * Что делает [step]:
 *  1. Адаптивное сглаживание в Oklab: мелкие изменения гасятся сильнее (шум, зерно), крупные проходят
 *     быстро (вспышки); яркость растёт быстрее, чем спадает.
 *  2. Переход в линейный свет с «масштабированием вместо обрезки», яркость, мягкое ограничение ослепления.
 *  3. Временной дизеринг в тёмной области, перевод в байты.
 */
class LabPipeline(private val gw: Int, private val gh: Int) {

    var zoneCount = 0
        private set

    private var params = LabParams()
    private var zoneKey = ""
    private var lutGamma = -1.0
    private var lut = LabColor.buildDecodeLut(2.2)

    // Производные от настроек значения
    private var gainC = 1.15f
    private var tauRise = 72f
    private var tauFall = 180f
    private var deadband = 0.006f
    private var smallDamp = 3f
    private var dither = false

    // Зоны: для диода z записи с eStart[z] по eStart[z+1]-1 в массивах eIdx / eW / eCore
    private var eStart = IntArray(1)
    private var eIdx = IntArray(0)
    private var eW = FloatArray(0)
    private var eCore = BooleanArray(0)

    // Данные пикселей текущего кадра
    private val pr = FloatArray(gw * gh)
    private val pg = FloatArray(gw * gh)
    private val pb = FloatArray(gw * gh)
    private val pf = FloatArray(gw * gh) // вес пикселя (меньше у бликов)
    private val ph = FloatArray(gw * gh) // «пересвеченность» пикселя 0..1

    // Состояние зон (по три числа L, a, b на зону)
    private var zr = FloatArray(0)
    private var zg = FloatArray(0)
    private var zb = FloatArray(0)
    private var tgt = FloatArray(0)
    private var prevTgt = FloatArray(0)
    private var held = FloatArray(0)
    private var cur = FloatArray(0)
    private var drive = FloatArray(0)
    private var ditherErr = FloatArray(0)

    private val tmp3 = FloatArray(3)
    private val hist = FloatArray(HUE_BINS + 1)
    private val prevHist = FloatArray(HUE_BINS + 1)
    private var havePrev = false
    private var snapNext = true
    private var powerGain = 1f

    // ---------- Настройка ----------

    fun configure(p: LabParams, g: LabGeometry) {
        if (p.gamma != lutGamma) {
            lut = LabColor.buildDecodeLut(p.gamma)
            lutGamma = p.gamma
        }
        params = p

        gainC = 0.7f + 0.9f * p.vivid.coerceIn(0, 100) / 100f
        tauRise = LabColor.lerp(120f, 25f, p.speed.coerceIn(0, 100) / 100f)
        tauFall = tauRise * 2.5f
        when (p.jitter) {
            0 -> { deadband = 0f; smallDamp = 1f }
            2 -> { deadband = 0.012f; smallDamp = 5f }
            else -> { deadband = 0.006f; smallDamp = 3f }
        }
        dither = p.outputFps >= DITHER_MIN_FPS

        val key = "$g|${p.edge}"
        if (key != zoneKey) {
            zoneKey = key
            rebuildZones(g, p.edge)
        }
    }

    /** Следующий кадр применить мгновенно (после паузы, при включении, при смене настроек зон). */
    fun requestSnap() {
        snapNext = true
    }

    /** Гасит внутреннее состояние: следующий кадр начнёт с чистого листа. */
    fun clear() {
        java.util.Arrays.fill(cur, 0f)
        java.util.Arrays.fill(held, 0f)
        java.util.Arrays.fill(prevTgt, 0f)
        java.util.Arrays.fill(ditherErr, 0f)
        havePrev = false
        snapNext = true
        powerGain = 1f
    }

    // ---------- Построение зон ----------

    private class ZoneBuf(val idx: IntArray, val w: FloatArray, val core: BooleanArray)

    private class Collector {
        var n = 0
        var idx = IntArray(128)
        var w = FloatArray(128)
        var core = BooleanArray(128)

        fun add(i: Int, wt: Float, isCore: Boolean) {
            if (n == idx.size) {
                idx = idx.copyOf(n * 2)
                w = w.copyOf(n * 2)
                core = core.copyOf(n * 2)
            }
            idx[n] = i
            w[n] = wt
            core[n] = isCore
            n++
        }

        fun build() = ZoneBuf(idx.copyOf(n), w.copyOf(n), core.copyOf(n))
    }

    private fun rebuildZones(g: LabGeometry, edge: Int) {
        val areaX = g.barsH.toFloat()
        val areaY = g.barsV.toFloat()
        val areaW = max(4, gw - 2 * g.barsH).toFloat()
        val areaH = max(4, gh - 2 * g.barsV).toFloat()

        val mx = areaW * g.marginX / 100f
        val my = areaH * g.marginY / 100f
        val ax0 = areaX + mx
        val ax1 = areaX + areaW - mx
        val ay0 = areaY + my
        val ay1 = areaY + areaH - my
        val depthX = max(1f, areaW * g.depth / 100f)
        val depthY = max(1f, areaH * g.depth / 100f)
        val power = 1f + 2f * edge.coerceIn(0, 100) / 100f

        val zones = ArrayList<ZoneBuf>(g.top + g.right + g.bottom + g.left)
        // Порядок как в остальных движках: по часовой, верх слева направо, право сверху вниз,
        // низ справа налево, лево снизу вверх.
        for (i in 0 until g.top) zones += edgeZone(true, true, i, g.top, ax0, ax1, ay0, depthY, power)
        for (i in 0 until g.right) zones += edgeZone(false, false, i, g.right, ay0, ay1, ax1, depthX, power)
        for (i in 0 until g.bottom) zones += edgeZone(true, false, g.bottom - 1 - i, g.bottom, ax0, ax1, ay1, depthY, power)
        for (i in 0 until g.left) zones += edgeZone(false, true, g.left - 1 - i, g.left, ay0, ay1, ax0, depthX, power)

        if (g.reverse) zones.reverse()
        if (zones.isNotEmpty() && g.offset > 0) {
            Collections.rotate(zones, -(g.offset % zones.size))
        }

        val n = zones.size
        var total = 0
        for (z in zones) total += z.idx.size
        eStart = IntArray(n + 1)
        eIdx = IntArray(total)
        eW = FloatArray(total)
        eCore = BooleanArray(total)
        var pos = 0
        for (i in 0 until n) {
            eStart[i] = pos
            val z = zones[i]
            System.arraycopy(z.idx, 0, eIdx, pos, z.idx.size)
            System.arraycopy(z.w, 0, eW, pos, z.w.size)
            System.arraycopy(z.core, 0, eCore, pos, z.core.size)
            pos += z.idx.size
        }
        eStart[n] = pos

        if (n != zoneCount) {
            zoneCount = n
            zr = FloatArray(n)
            zg = FloatArray(n)
            zb = FloatArray(n)
            tgt = FloatArray(n * 3)
            prevTgt = FloatArray(n * 3)
            held = FloatArray(n * 3)
            cur = FloatArray(n * 3)
            drive = FloatArray(n * 3)
            ditherErr = FloatArray(n * 3)
            havePrev = false
        }
        snapNext = true
    }

    /**
     * Зона одного диода у края.
     *
     * @param horizontal true для верха и низа (диоды идут вдоль оси X), false для левого и правого краёв
     * @param fromStart  true для верха и левого края (край стоит у начала оси), false для низа и правого
     * @param k          номер ячейки вдоль края (0 = у начала оси)
     * @param a0,a1      протяжённость области вдоль края
     * @param edgePos    координата самого края поперёк (Y для верха и низа, X для боков)
     * @param depth      глубина захвата в клетках
     */
    private fun edgeZone(
        horizontal: Boolean, fromStart: Boolean, k: Int, n: Int,
        a0: Float, a1: Float, edgePos: Float, depth: Float, power: Float
    ): ZoneBuf {
        val c = Collector()
        val cell = (a1 - a0) / n
        val center = a0 + cell * (k + 0.5f)
        val firstCell = k == 0
        val lastCell = k == n - 1

        // Диапазон вдоль края: до соседних центров, на краях ленты до самого края картинки
        val alongLo = if (firstCell) a0 else max(a0, center - cell)
        val alongHi = if (lastCell) a1 else min(a1, center + cell)
        // Диапазон вглубь
        val depthLo = if (fromStart) edgePos else edgePos - depth
        val depthHi = if (fromStart) edgePos + depth else edgePos

        val alongMax = if (horizontal) gw else gh
        val depthMax = if (horizontal) gh else gw
        val aFrom = max(0, floor(alongLo).toInt())
        val aTo = min(alongMax - 1, Math.ceil(alongHi.toDouble()).toInt())
        val dFrom = max(0, floor(depthLo).toInt())
        val dTo = min(depthMax - 1, Math.ceil(depthHi.toDouble()).toInt())

        for (dp in dFrom..dTo) {
            val dist = if (fromStart) (dp + 0.5f) - edgePos else edgePos - (dp + 0.5f)
            if (dist < 0f || dist >= depth) continue
            val depthW = Math.pow((1f - dist / depth).toDouble(), power.toDouble()).toFloat()

            for (ap in aFrom..aTo) {
                val ac = ap + 0.5f
                if (ac < a0 || ac > a1) continue
                val off = ac - center
                val along = when {
                    firstCell && off < 0f -> 1f
                    lastCell && off > 0f -> 1f
                    else -> max(0f, 1f - abs(off) / cell)
                }
                if (along <= 0f) continue
                val w = along * depthW
                if (w < MIN_WEIGHT) continue
                val isCore = (firstCell && off < 0f) || (lastCell && off > 0f) || abs(off) <= cell * 0.5f
                val index = if (horizontal) dp * gw + ap else ap * gw + dp
                c.add(index, w, isCore)
            }
        }
        return c.build()
    }

    // ---------- Шаг 1: новый кадр ----------

    /**
     * Обрабатывает захваченный кадр: пиксели ARGB, сетка gw на gh. Возвращает false, если делать нечего.
     * [hdr] включает заготовку под HDR (пока она ничего не меняет).
     */
    fun ingest(pixels: IntArray, hdr: Boolean): Boolean {
        val n = zoneCount
        if (n == 0 || pixels.size < gw * gh) return false
        val accent = params.accentHighlights
        val total = gw * gh

        // 1. Пиксели в линейный свет, оценка бликов
        for (i in 0 until total) {
            val c = pixels[i]
            var r = lut[(c shr 16) and 0xFF]
            var g = lut[(c shr 8) and 0xFF]
            var b = lut[c and 0xFF]
            if (hdr) {
                r = LabHdr.mapLinear(r)
                g = LabHdr.mapLinear(g)
                b = LabHdr.mapLinear(b)
            }
            val y = LabColor.luma(r, g, b)
            val mxc = LabColor.maxOf3(r, g, b)
            val mnc = LabColor.minOf3(r, g, b)
            val chroma = (mxc - mnc) / (mxc + 1e-4f)
            // Пересвеченный = яркий и почти без цвета (белое пятно). Яркий цветной (огонь) блик слабее.
            val h = LabColor.smoothstep(HL_LO, HL_HI, y) * (1f - 0.7f * chroma)
            if (accent) {
                pf[i] = 1f
                ph[i] = h
            } else {
                if (y > SOFT_KNEE) {
                    // Мягкое сжатие яркости с сохранением цвета: до колена как есть, дальше плавно к 1
                    val x = (y - SOFT_KNEE) / (1f - SOFT_KNEE)
                    val ys = SOFT_KNEE + (1f - SOFT_KNEE) * x / (1f + x)
                    val k = ys / y
                    r *= k; g *= k; b *= k
                }
                pf[i] = 1f - 0.5f * h
                ph[i] = 0f
            }
            pr[i] = r; pg[i] = g; pb[i] = b
        }

        // 2. Среднее по зоне с весами (в линейном свете)
        var meanR = 0f
        var meanG = 0f
        var meanB = 0f
        for (z in 0 until n) {
            var sw = 0f
            var sr = 0f
            var sg = 0f
            var sb = 0f
            for (e in eStart[z] until eStart[z + 1]) {
                val p = eIdx[e]
                var w = eW[e] * pf[p]
                // Режим «акцент»: блик влияет только на свою зону, на соседей не затекает
                if (accent && !eCore[e]) w *= (1f - ph[p])
                sw += w
                sr += w * pr[p]
                sg += w * pg[p]
                sb += w * pb[p]
            }
            if (sw > 1e-6f) {
                sr /= sw; sg /= sw; sb /= sw
            }
            zr[z] = sr; zg[z] = sg; zb[z] = sb
            meanR += sr; meanG += sg; meanB += sb
        }
        val inv = 1f / n
        meanR *= inv; meanG *= inv; meanB *= inv
        val meanY = LabColor.luma(meanR, meanG, meanB)

        // 3. Фоновый свет в тёмных зонах: плавно, без порога
        val glow = params.glow
        // Полностью чёрный кадр (титры, затемнение) фоном не подсвечиваем; уже со средней яркости ~3/255 фон включается
        val sceneFactor = LabColor.smoothstep(0.00005f, 0.0006f, meanY)
        if (glow > 0f && sceneFactor > 0f) {
            val my = max(meanY, 1e-5f)
            val ur = meanR / my
            val ug = meanG / my
            val ub = meanB / my
            for (z in 0 until n) {
                val y = LabColor.luma(zr[z], zg[z], zb[z])
                val amount = glow * sceneFactor * (1f - LabColor.smoothstep(0f, 3f * glow, y))
                if (amount > 0f) {
                    // Направление цвета: сама зона; цвет сцены подмешивается лишь чуть-чуть и только
                    // если зона почти чёрная (иначе блик в кадре красил бы фон всех тёмных зон)
                    val prior = GLOW_SCENE_PRIOR * glow
                    val dr = zr[z] + prior * ur
                    val dg = zg[z] + prior * ug
                    val db = zb[z] + prior * ub
                    val dy = y + prior
                    zr[z] += amount * dr / dy
                    zg[z] += amount * dg / dy
                    zb[z] += amount * db / dy
                }
            }
        }

        // 4. Oklab, насыщенность, гистограмма оттенков
        java.util.Arrays.fill(hist, 0f)
        var chromaSum = 0f
        for (z in 0 until n) {
            LabColor.rgbToOklab(zr[z], zg[z], zb[z], tmp3, 0)
            val ca = tmp3[1]
            val cb = tmp3[2]
            val chroma = Math.sqrt((ca * ca + cb * cb).toDouble()).toFloat()
            if (chroma > 0.01f) {
                val hue = Math.atan2(cb.toDouble(), ca.toDouble()) // -pi..pi
                var bin = ((hue / (2.0 * Math.PI) + 0.5) * HUE_BINS).toInt()
                if (bin >= HUE_BINS) bin = HUE_BINS - 1
                if (bin < 0) bin = 0
                hist[bin] += chroma
                chromaSum += chroma
            }
            val i = z * 3
            tgt[i] = tmp3[0]
            tgt[i + 1] = ca * gainC
            tgt[i + 2] = cb * gainC
        }
        if (chromaSum > 0.02f) {
            for (b in 0 until HUE_BINS) hist[b] /= chromaSum
            hist[HUE_BINS] = 0f
        } else {
            for (b in 0 until HUE_BINS) hist[b] = 0f
            hist[HUE_BINS] = 1f // почти без цвета: «нейтральная» корзина
        }

        // 5. Смена сцены: изменилась большая часть зон и «отпечаток» оттенков стал другим
        var cut = snapNext
        if (!cut && havePrev) {
            var changed = 0
            for (z in 0 until n) {
                val i = z * 3
                if (LabColor.deltaE(tgt[i], tgt[i + 1], tgt[i + 2], prevTgt[i], prevTgt[i + 1], prevTgt[i + 2]) > CUT_ZONE_DE) {
                    changed++
                }
            }
            if (changed.toFloat() / n >= CUT_ZONE_FRACTION) {
                var dist = 0f
                for (b in 0..HUE_BINS) dist += abs(hist[b] - prevHist[b])
                if (0.5f * dist >= CUT_HIST_DIST) cut = true
            }
        }

        // 6. Мёртвая зона против дрожи и запоминание кадра
        if (cut) {
            System.arraycopy(tgt, 0, held, 0, n * 3)
            System.arraycopy(tgt, 0, cur, 0, n * 3)
            snapNext = false
        } else {
            for (z in 0 until n) {
                val i = z * 3
                if (LabColor.deltaE(tgt[i], tgt[i + 1], tgt[i + 2], held[i], held[i + 1], held[i + 2]) >= deadband) {
                    held[i] = tgt[i]
                    held[i + 1] = tgt[i + 1]
                    held[i + 2] = tgt[i + 2]
                }
            }
        }
        System.arraycopy(tgt, 0, prevTgt, 0, n * 3)
        System.arraycopy(hist, 0, prevHist, 0, HUE_BINS + 1)
        havePrev = true
        return true
    }

    // ---------- Шаг 2: вывод на ленту ----------

    /** Продвигает цвета на [dtMs] миллисекунд и записывает байты R, G, B всех диодов в [out]. */
    fun step(dtMs: Float, out: ByteArray) {
        val n = zoneCount
        if (n == 0 || out.size < n * 3) return
        val dt = dtMs.coerceIn(1f, 250f)

        // 1. Адаптивное сглаживание в Oklab
        for (z in 0 until n) {
            val i = z * 3
            val hl = held[i]
            val ha = held[i + 1]
            val hb = held[i + 2]
            val cl = cur[i]
            val d = LabColor.deltaE(hl, ha, hb, cl, cur[i + 1], cur[i + 2])
            val base = if (hl > cl) tauRise else tauFall
            // Мелкие изменения гасим сильнее (шум, зерно), крупные пропускаем быстрее (вспышки)
            var f = LabColor.lerp(smallDamp, 1f, LabColor.smoothstep(0.02f, 0.10f, d))
            f *= LabColor.lerp(1f, 0.5f, LabColor.smoothstep(0.10f, 0.30f, d))
            val a = dt / (base * f + dt)
            cur[i] = cl + (hl - cl) * a
            cur[i + 1] += (ha - cur[i + 1]) * a
            cur[i + 2] += (hb - cur[i + 2]) * a
        }

        // 2. В линейный свет внутри возможностей ленты, яркость
        var peakSum = 0f
        val gainNow = params.brightness * powerGain
        for (z in 0 until n) {
            val i = z * 3
            LabColor.oklabToRgbGamut(cur[i], cur[i + 1], cur[i + 2], tmp3, 0)
            val r = tmp3[0]
            val g = tmp3[1]
            val b = tmp3[2]
            peakSum += LabColor.maxOf3(r, g, b)
            drive[i] = r * gainNow
            drive[i + 1] = g * gainNow
            drive[i + 2] = b * gainNow
        }

        // 3. Защита от ослепления: если почти вся лента светит на максимум, чуть приглушаем (медленно, без пульсаций)
        val avgPeak = peakSum / n
        val gTarget = if (avgPeak > POWER_KNEE) {
            1f - POWER_MAX_CUT * (avgPeak - POWER_KNEE) / (1f - POWER_KNEE)
        } else {
            1f
        }
        powerGain += (gTarget - powerGain) * (dt / (POWER_TAU_MS + dt))

        // 4. Байты, в тёмной области с временным дизерингом
        for (k in 0 until n * 3) {
            val v = drive[k] * 255f
            var q: Int
            if (v <= 0.001f) {
                ditherErr[k] = 0f
                q = 0
            } else if (dither && v < DITHER_MAX) {
                val acc = v + ditherErr[k]
                q = floor((acc + 0.5f).toDouble()).toInt()
                if (q < 0) q = 0
                if (q > 255) q = 255
                ditherErr[k] = (acc - q).coerceIn(-1f, 1f)
            } else {
                q = Math.round(v)
                if (q > 255) q = 255
                ditherErr[k] = 0f
            }
            out[k] = q.toByte()
        }
    }

    // ---------- Для тестов и диагностики ----------

    internal fun zoneEntryCount(): Int = eIdx.size

    /** Сумма весов пикселя по всем зонам (проверка: вдоль края должна выходить около 1). */
    internal fun pixelWeightSum(pixelIndex: Int): Float {
        var s = 0f
        for (e in eIdx.indices) if (eIdx[e] == pixelIndex) s += eW[e]
        return s
    }

    /** Текущий цвет зоны в линейном свете (после сглаживания, до яркости). */
    internal fun currentLinear(z: Int, out: FloatArray) {
        val i = z * 3
        LabColor.oklabToRgbGamut(cur[i], cur[i + 1], cur[i + 2], out, 0)
    }

    private companion object {
        const val MIN_WEIGHT = 0.003f
        const val HUE_BINS = 12
        const val DITHER_MIN_FPS = 45
        const val DITHER_MAX = 48f // выше этого значения (из 255) шаги не видны, дизеринг не нужен

        // Блики
        const val SOFT_KNEE = 0.5f
        const val HL_LO = 0.45f
        const val HL_HI = 0.85f

        // Фоновый свет: доля цвета сцены в «подсказке» направления (0 = только цвет самой зоны)
        const val GLOW_SCENE_PRIOR = 0.15f

        // Смена сцены
        const val CUT_ZONE_DE = 0.12f
        const val CUT_ZONE_FRACTION = 0.5f
        const val CUT_HIST_DIST = 0.4f

        // Защита от ослепления
        const val POWER_KNEE = 0.45f
        const val POWER_MAX_CUT = 0.28f
        const val POWER_TAU_MS = 600f
    }
}
