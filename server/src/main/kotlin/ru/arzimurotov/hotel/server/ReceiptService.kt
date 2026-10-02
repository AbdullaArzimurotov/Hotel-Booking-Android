package ru.arzimurotov.hotel.server

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import ru.arzimurotov.hotel.domain.*

/** PDF выпускается после COMMIT. Кириллица и переносы строк работают без системных шрифтов.
 * Реквизиты берутся из snapshot; аннулирование не переписывает первоначальный документ. */
class ReceiptService(private val bookings:BookingService,
    private val generator:((Booking,Instant)->ByteArray)? = null) {
    suspend fun download(user:UserProfile,id:String):ByteArray {
        val (booking,time)=bookings.receipt(user,id)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { generator?.invoke(booking,time) ?: render(booking,time) }
    }
    fun render(b:Booking,paidAt:Instant):ByteArray = PDDocument().use { doc ->
        val font=javaClass.getResourceAsStream("/fonts/DejaVuSans.ttf").use { PDType0Font.load(doc,requireNotNull(it)) }
        var stream:PDPageContentStream?=null;var y=0f;var page=0
        fun newPage() {
            stream?.close();val p=PDPage(PDRectangle.A4);doc.addPage(p);page++
            stream=PDPageContentStream(doc,p);y=p.mediaBox.height-50
            stream!!.beginText();stream!!.setFont(font,9f);stream!!.newLineAtOffset(50f,25f);stream!!.showText("Гостиница - учебный проект | Страница $page");stream!!.endText()
        }
        fun line(text:String,size:Float=11f) {
            // Контрольные символы/неподдерживаемые emoji не должны ломать уже оплаченный документ.
            val symbols=text.codePoints().toArray().map { cp ->
                val s=if(Character.isISOControl(cp)) " " else String(Character.toChars(cp))
                try {font.encode(s);s} catch(_:IllegalArgumentException) {"?"}
            }
            var part=""
            fun write() { if(y<55) newPage();stream!!.beginText();stream!!.setFont(font,size);stream!!.newLineAtOffset(50f,y);stream!!.showText(part);stream!!.endText();y-=size*1.5f;part="" }
            // Посимвольный перенос обрабатывает и длинный идентификатор без пробелов.
            symbols.forEach { ch->if(font.getStringWidth(part+ch)/1000*size>PDRectangle.A4.width-100) write();part+=ch }
            write();y-=4
        }
        fun money(v:Long)=BigDecimal.valueOf(v,2).toPlainString()+" "+b.currency
        newPage()
        line("ПОДТВЕРЖДЕНИЕ ДЕМОНСТРАЦИОННОЙ ОПЛАТЫ",14f)
        line("Демонстрационный документ. Не является фискальным чеком.")
        line("Реальные деньги не списаны. Документ не подтверждает реальную услугу.")
        line("Бронирование: ${b.number}")
        line("Клиент: ${b.snapshot.customer}");line("Email: ${b.snapshot.email}")
        line("Гостиница: ${b.snapshot.hotelName}");line("Адрес: ${b.snapshot.address}")
        line("Тип номера: ${RoomKind.valueOf(b.snapshot.kind).title}; номера: ${b.snapshot.roomNumbers.joinToString()}")
        line("Проживание: ${b.checkIn} - ${b.checkOut}; взрослых: ${b.adults}; детей: ${b.children}")
        line("За ночь / номер: ${money(b.snapshot.nightlyPrice)}; проживание: ${money(b.snapshot.roomTotal)}")
        b.snapshot.services.forEach { s->line("Услуга: ${s.name} - ${money(s.total)}");s.transfer?.let { line("Аэропорт: ${it.airport}; рейс: ${it.flight}; встреча: ${it.pickupAt}; телефон: ${it.phone}") } }
        line("ИТОГО: ${money(b.total)}",14f)
        line("Оплата: ${paidAt.atZone(ZoneId.of(b.snapshot.timezone))}")
        line("Способ: ONLINE_DEMO; исходный статус: PAID")
        line("Транзакция: ${b.transactionId}");line("Документ: ${b.receiptId}")
        line("При отмене заказа актуальное аннулирование отображается в приложении. Эта квитанция сохраняет исходные реквизиты оплаты.")
        stream?.close();ByteArrayOutputStream().use { doc.save(it);it.toByteArray() }
    }
}
