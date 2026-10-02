from pathlib import Path
from html import escape
import re
import math
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate, Frame, PageTemplate, Paragraph, Spacer, Table,
    TableStyle, Flowable, KeepTogether, PageBreak, CondPageBreak,
)

ROOT = Path(__file__).resolve().parents[1]
FONT = Path('/System/Library/Fonts/Supplemental')
# Windows: стандартные Times New Roman. PDF_PATH задаёт только выход, не секреты.
import os
if os.name == 'nt': FONT = Path(os.environ.get('WINDIR', 'C:/Windows')) / 'Fonts'
for name, file in [('TNR', 'Times New Roman.ttf'),
                   ('TNR-Bold', 'Times New Roman Bold.ttf')]:
    if os.name == 'nt': file = {'Times New Roman.ttf':'times.ttf','Times New Roman Bold.ttf':'timesbd.ttf'}[file]
    pdfmetrics.registerFont(TTFont(name, str(FONT / file)))
pdfmetrics.registerFontFamily('TNR', normal='TNR', bold='TNR-Bold', italic='TNR')
PW, PH = A4
L, R, T, B = 30*mm, 15*mm, 20*mm, 20*mm
W, H = PW-L-R, PH-T-B

ST = {
    'body': ParagraphStyle('body', fontName='TNR', fontSize=14, leading=21,
        alignment=TA_JUSTIFY, firstLineIndent=12.5*mm, allowWidows=0,
        allowOrphans=0, spaceAfter=3),
    'title': ParagraphStyle('title', fontName='TNR-Bold', fontSize=14,
        leading=21, alignment=TA_CENTER, spaceAfter=12, keepWithNext=True),
    'h2': ParagraphStyle('h2', fontName='TNR-Bold', fontSize=14, leading=21,
        alignment=TA_LEFT, spaceBefore=12, spaceAfter=6, keepWithNext=True),
    'h3': ParagraphStyle('h3', fontName='TNR-Bold', fontSize=14, leading=21,
        alignment=TA_LEFT, spaceBefore=9, spaceAfter=3, keepWithNext=True),
    'cell': ParagraphStyle('cell', fontName='TNR', fontSize=14, leading=21,
        alignment=TA_LEFT, allowWidows=1, allowOrphans=1),
    'cellhead': ParagraphStyle('cellhead', fontName='TNR-Bold', fontSize=14,
        leading=21, alignment=TA_CENTER),
    'caption': ParagraphStyle('caption', fontName='TNR', fontSize=14,
        leading=21, alignment=TA_LEFT, spaceBefore=9, spaceAfter=6,
        keepWithNext=False),
    'source': ParagraphStyle('source', fontName='TNR', fontSize=14,
        leading=21, alignment=TA_LEFT, spaceAfter=9, splitLongWords=True),
}


def rich(s):
    s = s.replace('—', '-').replace('–', '-').replace('‑', '-')
    s = s.replace('→', ' - ')
    return escape(s).replace('`', '')


def footer(canvas, doc):
    canvas.setFont('TNR', 12)
    canvas.drawCentredString(PW/2, 10*mm, str(doc.page))


def arrow(c, pts, double=False, dashed=False):
    c.setStrokeColor(colors.black)
    c.setLineWidth(.7)
    if dashed:
        c.setDash(3, 2)
    path = c.beginPath()
    path.moveTo(*pts[0])
    for p in pts[1:]:
        path.lineTo(*p)
    c.drawPath(path)
    c.setDash()
    def head(p, q):
        a = math.atan2(q[1]-p[1], q[0]-p[0])
        for d in (-.45, .45):
            c.line(q[0], q[1], q[0]-5*math.cos(a+d), q[1]-5*math.sin(a+d))
    head(pts[-2], pts[-1])
    if double:
        head(pts[1], pts[0])


def label(c, text, x, y, align='center', bold=False):
    c.setFont('TNR-Bold' if bold else 'TNR', 14)
    c.setFillColor(colors.black)
    if align == 'left':
        c.drawString(x, y, text)
    else:
        c.drawCentredString(x, y, text)


def box(c, x, y, w, h, lines, gray=False):
    if isinstance(lines, str):
        lines = [lines]
    c.setFillColor(colors.HexColor('#f2f2f2') if gray else colors.white)
    c.setStrokeColor(colors.black)
    c.setLineWidth(.7)
    c.rect(x, y, w, h, stroke=1, fill=1)
    baseline = y+h/2+(len(lines)-1)*10.5-4
    for i, text in enumerate(lines):
        assert pdfmetrics.stringWidth(text, 'TNR', 14) < w-6, (text, w)
        label(c, text, x+w/2, baseline-i*21, bold=(i == 0))


class Figure(Flowable):
    def __init__(self, kind):
        super().__init__()
        self.kind = kind
        self.width = W
        self.height = {'component': 632, 'er_core': 625, 'er_extra': 628,
                       'booking': 616, 'payment': 629}[kind]

    def draw(self):
        getattr(self, self.kind)()

    def component(self):
        c = self.canv
        nodes = [(135,574,['Android UI','Compose / навигация']),
                 (135,504,['ViewModel','StateFlow']),
                 (135,434,['Domain','Сценарии']),
                 (135,364,['Data','Репозитории']),
                 (0,280,['Room: этап 7','Описания / кэш']),
                 (266,280,['Ktor Client','HTTPS / JSON, PDF']),
                 (266,204,['Маршруты Ktor','JWT / права']),
                 (266,136,['Сервисы сервера','Правила / транзакции']),
                 (266,68,['SQL-репозитории','Exposed JDBC']),
                 (266,0,['PostgreSQL','Основные данные'])]
        for x,y,lines in nodes:
            box(c,x,y,174,48,lines)
        box(c,330,574,110,48,['Maps SDK','Этап 7'],True)
        for y in (574,504,434):
            arrow(c,[(222,y),(222,y-22)],double=True)
        arrow(c,[(309,598),(330,598)],double=True)
        arrow(c,[(174,364),(87,364),(87,328)],double=True)
        arrow(c,[(270,364),(353,364),(353,328)],double=True)
        for y, target in [(280,252),(204,184),(136,116),(68,48)]:
            arrow(c,[(353,y),(353,target)],double=True)

    def er_core(self):
        c = self.canv
        bw,bh=132,44
        left=[(570,'Государства'),(490,'Города'),(410,'Гостиницы'),(330,'Типы номеров'),(250,'Номера')]
        for y,name in left: box(c,0,y,bw,bh,name)
        for top,bottom in zip(left,left[1:]):
            arrow(c,[(66,top[0]),(66,bottom[0]+bh)]);label(c,'1:N',92,(top[0]+bottom[0]+bh)/2-4)
        for x,y,name in [(300,570,'Пользователи'),(300,330,'Тарифы'),(300,250,'Бронирования'),
                         (0,170,'Блокировки'),(0,90,'Состав брони'),(300,170,'Демоплатежи'),
                         (300,90,'PDF-документы'),(150,0,'Услуги брони')]:
            box(c,x,y,bw,bh,name)
        arrow(c,[(132,352),(300,352)]);label(c,'1:N',216,363)
        arrow(c,[(366,330),(366,294)]);label(c,'1:N',392,308)
        arrow(c,[(432,592),(445,592),(445,272),(432,272)]);label(c,'1:N',413,446)
        arrow(c,[(66,250),(66,214)]);label(c,'1:N',92,228)
        arrow(c,[(132,272),(145,272),(145,112),(132,112)]);label(c,'1:N',170,156)
        arrow(c,[(300,272),(230,272),(230,112),(132,112)]);label(c,'1:N',257,205)
        arrow(c,[(366,250),(366,214)]);label(c,'1:0..1',401,229)
        arrow(c,[(366,170),(366,134)]);label(c,'1:1',396,148)
        arrow(c,[(432,272),(457,272),(457,55),(216,55),(216,44)]);label(c,'1:N',342,63)

    def er_extra(self):
        c=self.canv
        bw,bh=132,44
        for x,y,title in [(0,555,'Города'),(300,555,'Гостиницы'),
                (0,455,'Места города'),(300,455,'Фото гостиницы'),
                (150,355,['Гостиница -','место']),
                (0,255,'Типы номеров'),(300,255,'Виды услуг'),
                (0,155,'Фото типа'),(150,155,['Услуги','гостиницы']),
                (300,75,'Удобства'),(150,0,['Тип -','удобство'])]:
            box(c,x,y,bw,bh,title)
        arrow(c,[(66,555),(66,499)]); label(c,'1:N',92,522)
        arrow(c,[(366,555),(366,499)]); label(c,'1:N',392,522)
        arrow(c,[(66,455),(66,377),(150,377)]); label(c,'1:N',92,416)
        arrow(c,[(300,577),(216,577),(216,399)]); label(c,'1:N',242,463)
        arrow(c,[(66,255),(66,199)]); label(c,'1:N',92,219)
        arrow(c,[(366,255),(366,220),(216,220),(216,199)]); label(c,'1:N',312,232)
        arrow(c,[(432,577),(445,577),(445,177),(282,177)])
        label(c,'1:N',405,309)
        arrow(c,[(132,277),(141,277),(141,22),(150,22)])
        label(c,'1:N',164,95)
        arrow(c,[(366,75),(366,22),(282,22)]); label(c,'1:N',316,53)

    def lifelines(self, c, top, bottom=12):
        for x,name in [(50,'Android'),(220,'Ktor API'),(398,'PostgreSQL')]:
            box(c,x-48,top,96,38,name,True)
            c.setDash(3,3)
            c.line(x,top,x,bottom)
            c.setDash()

    def msg(self,c,start,end,y,text,response=False):
        xs={'a':50,'k':220,'d':398}
        x1,x2=xs[start],xs[end]
        if start == end:
            arrow(c,[(x1,y),(x1+30,y),(x1+30,y-14),(x1,y-14)])
            label(c,text,x1+37,y-5,align='left')
        else:
            label(c,text,(x1+x2)/2,y+8)
            arrow(c,[(x1,y),(x2,y)],dashed=response)

    def alt(self,c,top,split,bottom):
        c.setStrokeColor(colors.HexColor('#777777'))
        c.rect(0,bottom,440,top-bottom,fill=0,stroke=1)
        c.line(0,split,440,split)

    def booking(self):
        c=self.canv
        self.lifelines(c,574)
        self.msg(c,'a','k',534,'POST /bookings')
        self.msg(c,'k','k',490,'JWT, ключ и ввод')
        self.msg(c,'k','d',447,'BEGIN; тип, номера: lock')
        self.msg(c,'k','d',403,'Даты, тариф, услуги')
        self.alt(c,379,249,122)
        label(c,'alt: конфликт / ошибка',9,359,align='left',bold=True)
        self.msg(c,'k','d',325,'ROLLBACK')
        self.msg(c,'k','a',279,'409 / ошибка',True)
        label(c,'else: доступно',9,229,align='left',bold=True)
        self.msg(c,'k','d',196,'INSERT; COMMIT')
        self.msg(c,'k','a',150,'ID, сумма, статус',True)
        p=Paragraph('ONLINE_DEMO: ожидание оплаты, 15 минут.<br/>PAY_AT_HOTEL: подтверждено, без оплаты.',ST['cell'])
        p.wrap(432,80)
        c.setFillColor(colors.white)
        c.rect(0,25,440,62,fill=1,stroke=0)
        p.drawOn(c,5,34)

    def payment(self):
        c=self.canv
        c.translate(0,-22)
        self.lifelines(c,612,bottom=34)
        self.msg(c,'a','k',571,'POST /payments/demo')
        self.msg(c,'k','k',531,'JWT, права доступа')
        self.msg(c,'k','d',491,'BEGIN; номер, заказ')
        self.msg(c,'k','k',451,'Метод, статус, срок')
        self.alt(c,431,317,205)
        label(c,'alt: заказ действителен',9,412,align='left',bold=True)
        self.msg(c,'k','d',378,'Платеж, снимок; COMMIT')
        self.msg(c,'k','a',338,'PAID, receiptId',True)
        label(c,'else: отказ',9,298,align='left',bold=True)
        self.msg(c,'k','d',270,'ROLLBACK')
        self.msg(c,'k','a',229,'403/409',True)
        c.setFillColor(colors.white)
        c.rect(0,177,440,23,fill=1,stroke=0)
        label(c,'Далее - только после успешного платежа',220,181)
        self.msg(c,'a','k',157,'GET /receipts/{id}')
        self.msg(c,'k','d',117,'Снимок, права')
        self.msg(c,'k','k',77,'Формирование PDF')
        self.msg(c,'k','a',32,'PDF-документ',True)


def table(lines):
    data=[]
    for line in lines:
        vals=[x.strip() for x in line.strip().strip('|').split('|')]
        if all(re.fullmatch(r':?-+:?',v) for v in vals):
            continue
        style=ST['cellhead'] if not data else ST['cell']
        data.append([Paragraph(rich(x),style) for x in vals])
    result=Table(data,colWidths=[W*.32,W*.68],repeatRows=1,hAlign='LEFT',
                 splitByRow=True)
    result.setStyle(TableStyle([
        ('GRID',(0,0),(-1,-1),.5,colors.black),
        ('BACKGROUND',(0,0),(-1,0),colors.HexColor('#f2f2f2')),
        ('VALIGN',(0,0),(-1,-1),'TOP'),
        ('LEFTPADDING',(0,0),(-1,-1),6),
        ('RIGHTPADDING',(0,0),(-1,-1),6),
        ('TOPPADDING',(0,0),(-1,-1),6),
        ('BOTTOMPADDING',(0,0),(-1,-1),6),
    ]))
    return result


def source_para(text):
    match=re.search(r'URL: (https?://\S+)',text)
    if not match:
        return Paragraph(rich(text),ST['source'])
    url=match.group(1)
    before=text[:match.start()].strip()
    after=text[match.end():].strip()
    # Explicit URL with discretionary breaks: no shrinking of the typeface.
    display=escape(url)
    for character in ('/', '-', '?', '=', '_'):
        display=display.replace(character,character+'<wbr/>')
    formatted=rich(before)+'<br/>URL: <link href="'+escape(url,quote=True)+'">'+display+'</link><br/>'+rich(after)
    return Paragraph(formatted,ST['source'])


def build(source,output,title):
    doc=BaseDocTemplate(str(ROOT/output),pagesize=A4,leftMargin=L,rightMargin=R,
        topMargin=T,bottomMargin=B,title=title,author='Арзимуротов А.М.',
        subject='Курсовая работа: Мобильное приложение «Гостиница»')
    frame=Frame(L,B,W,H,leftPadding=0,rightPadding=0,topPadding=0,bottomPadding=0)
    doc.addPageTemplates(PageTemplate(id='plain',frames=frame,onPage=footer))
    lines=(ROOT/source).read_text(encoding='utf-8').splitlines()
    story=[]
    sources=False
    i=0
    while i<len(lines):
        s=lines[i].strip()
        if not s:
            i+=1
            continue
        if s.startswith('```'):
            i+=1
            while i<len(lines) and not lines[i].startswith('```'):
                i+=1
            i+=1
            continue
        if s.startswith('Рисунок '):
            caption=s
            i+=1
            while not lines[i].strip():
                i+=1
            kind=re.fullmatch(r'\[\[figure:(\w+)\]\]',lines[i].strip()).group(1)
            parts=[]
            if story and isinstance(story[-1],Paragraph) and story[-1].style is ST['h3']:
                parts.append(story.pop())
            parts.extend([Paragraph(rich(caption),ST['caption']),Figure(kind)])
            story.append(KeepTogether(parts))
            i+=1
            continue
        if re.match(r'^Таблица \d+\.\d+ - ',s):
            caption=Paragraph(rich(s),ST['caption'])
            i+=1
            while not lines[i].strip():
                i+=1
            rows=[]
            while i<len(lines) and lines[i].strip().startswith('|'):
                rows.append(lines[i].strip())
                i+=1
            tab=table(rows)
            tab.wrap(W,H)
            _,caption_height=caption.wrap(W,H)
            first_height=sum(tab._rowHeights[:2])
            story.extend([CondPageBreak(caption_height+first_height+18),caption,tab,Spacer(1,9)])
            continue
        if s.startswith('#'):
            level=len(s)-len(s.lstrip('#'))
            heading=s[level:].strip()
            if heading.startswith('Источники'):
                sources=True
            style=ST['title' if level==1 else 'h2' if level==2 else 'h3']
            story.append(Paragraph(rich(heading),style))
        elif sources:
            story.append(source_para(s))
        else:
            story.append(Paragraph(rich(s),ST['body']))
        i+=1
    doc.build(story)
    print(output)


if __name__=='__main__':
    output=os.environ.get('PDF_PATH', '../output/pdf/Архитектура_системы_этапы_3_6.pdf')
    (ROOT/output).parent.mkdir(parents=True,exist_ok=True)
    build(os.environ.get('PDF_SOURCE','docs/Раздел_2_Архитектура_этапы_3_6.md'), output, 'Разработка архитектуры системы - этапы 3-6')
