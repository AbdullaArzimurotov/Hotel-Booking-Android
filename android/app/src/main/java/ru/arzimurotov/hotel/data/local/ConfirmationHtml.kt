package ru.arzimurotov.hotel.data.local

import java.math.BigDecimal
import ru.arzimurotov.hotel.domain.Booking

/**
 * Независимый UTF-8 HTML с inline CSS читается без сети. Ввод пользователя HTML-экранируется.
 * Документ не утверждает банковскую оплату и не является фискальным чеком.
 */
object ConfirmationHtml {
    fun escape(v: String) =
        v.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun amount(value: Long, currency: String) =
        BigDecimal.valueOf(value, 2).toPlainString() + " " + escape(currency)

    fun render(b: Booking): String {
        fun row(label: String, value: String) =
            "<tr><td style='padding:10px;color:#627285'>${escape(label)}</td><td style='padding:10px'>${escape(value)}</td></tr>"
        return """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Подтверждение ${escape(b.number)}</title></head><body style="margin:0;background:#f4f6f9;font:16px Georgia,serif;color:#132f4c"><main style="max-width:680px;margin:24px auto;background:white;border-radius:16px;overflow:hidden"><header style="padding:28px;background:#132f4c;color:white"><p style="color:#dda763">ГОСТИНИЦА</p><h1>${if(b.paymentStatus=="PAID")"Подтверждение демооплаты" else "Подтверждение бронирования"}</h1><p>${escape(b.number)}</p></header><section style="padding:24px"><h2>${escape(b.snapshot.hotelName)}</h2><p>${escape(b.snapshot.address)}</p><table style="width:100%;border-collapse:collapse">${row("Клиент",b.snapshot.customer)}${row("Email",b.snapshot.email)}${row("Проживание",b.checkIn+" - "+b.checkOut)}${row("Номера",b.snapshot.roomNumbers.joinToString())}${row("Гости","Взрослых: ${b.adults}; детей: ${b.children}")}${row("Проживание",amount(b.snapshot.roomTotal,b.currency))}${b.snapshot.services.joinToString(""){row(it.name,amount(it.total,b.currency))}}${row("Способ оплаты",if(b.paymentMethod=="ONLINE_DEMO")"Демонстрационная онлайн-оплата" else "В гостинице")}${row("Статус оплаты",if(b.paymentStatus=="PAID")"Демооплата выполнена" else "Оплата не выполнена")}${row("Транзакция",b.transactionId ?: "Нет")}${row("Дата документа",java.time.Instant.ofEpochSecond(b.serverNow).toString())}</table><h2 style="padding:18px;background:#fff5e8">Итого: ${amount(b.total,b.currency)}</h2><p>Демонстрационный документ. Не является фискальным чеком. Деньги не списываются; реальная гостиница не бронируется.</p></section></main></body></html>"""
    }

    fun welcome(name: String, email: String) =
        "<!doctype html><html lang='ru'><meta charset='utf-8'><body style='font:18px Georgia;color:#132f4c;padding:30px'><h1>Добро пожаловать в «Гостиница»!</h1><p>${escape(name)}, ваш локальный аккаунт ${escape(email)} создан.</p><p>Это учебный сервис. Все заказы сохраняются только на вашем телефоне.</p></body></html>"
}
