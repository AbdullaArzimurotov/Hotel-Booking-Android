"""PDF 0.9.0: настоящие Times New Roman, 14 pt/21 pt, A4, TOC и векторные схемы.
Старый PDF-builder этапов 3–6 не изменяется. Источник Markdown остаётся редактируемым.
"""
from pathlib import Path
import re
import textwrap
from html import escape
from reportlab.platypus import BaseDocTemplate, Frame, PageTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, Flowable, Image, KeepTogether
from reportlab.platypus.tableofcontents import TableOfContents
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_LEFT,TA_CENTER
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from build_architecture_pdf import ROOT,ST,PW,PH,L,R,T,B,W,H,footer,box,arrow,source_para,rich

ST['code']=ParagraphStyle('program',fontName='TNR',fontSize=14,leading=21,alignment=TA_LEFT,spaceAfter=0)
ST['toc']=ParagraphStyle('contents',fontName='TNR',fontSize=14,leading=21,spaceAfter=4)
ST['cover']=ParagraphStyle('cover',parent=ST['title'])

class Document(BaseDocTemplate):
    def afterFlowable(self,flow):
        if isinstance(flow,Paragraph) and flow.style.name in ('title','h2'):
            name=flow.getPlainText()
            if name not in ('Содержание','Мобильное приложение «Гостиница»'):
                key='p'+str(self.seq.nextf('section'));self.canv.bookmarkPage(key)
                self.notify('TOCEntry',(0 if flow.style.name=='title' else 1,name,self.page,key))

class Diagram(Flowable):
    def __init__(self,kind):self.kind=kind;self.width=W;self.height=560
    def draw(self):
        c=self.canv
        c.setFont('TNR',14)
        if self.kind=='components':
            nodes=[(440,['Compose UI','Навигация']),(340,['ViewModel','StateFlow']),(240,['Контракты Domain','Local repositories']),(140,['LocalEngine / Admin','Правила и права']),(40,['Room / SQLite','Данные и транзакции'])]
            for y,lines in nodes:box(c,95,y,250,60,lines)
            for (y,_),(low,_) in zip(nodes,nodes[1:]):arrow(c,[(220,y),(220,low+60)],double=True)
            c.drawString(0,15,'Mapsforge: локальные assets; сеть не используется.')
        elif self.kind=='er':
            nodes=[(0,420,['catalog_entries','Справочники / JSON']),(250,420,['room_types','Типы номеров']),(250,310,['rooms / rates','Комнаты / тарифы']),(0,200,['local_users','Аккаунты']),(250,200,['bookings','Заказ / snapshot']),(250,90,['booking_rooms','Связи с комнатами']),(0,90,['payments / receipts','Оплата / документ'])]
            for x,y,lines in nodes:box(c,x,y,190,60,lines)
            arrow(c,[(190,450),(250,450)],dashed=True)
            arrow(c,[(345,420),(345,370)])
            arrow(c,[(190,230),(250,230)],dashed=True)
            arrow(c,[(345,200),(345,150)])
            arrow(c,[(250,230),(215,230),(215,120),(190,120)],dashed=True)
            arrow(c,[(440,340),(455,340),(455,120),(440,120)])
            c.drawString(0,45,'Также: room_blocks, hotel_services, hotel_places, audit.')
            c.drawString(0,15,'Пунктир: логическая связь; сплошная: SQL-связь.')
        else:
            xs=[65,230,400]
            for x,lines in zip(xs,[['Android UI'],['LocalEngine'],['Room / SQLite']]):
                box(c,x-63,475,126,48,lines);c.setDash(3,3);c.line(x,40,x,475);c.setDash()
            for y,a,b,label in [(432,0,1,'Вход, даты, ключ'),(382,1,2,'BEGIN'),(332,1,2,'Проверка и комнаты'),(282,1,2,'Заказ, snapshot'),(232,1,2,'COMMIT'),(182,1,0,'ID и состояние'),(132,0,1,'Демооплата'),(82,1,2,'Платёж + документ')]:
                p,q=xs[a],xs[b];arrow(c,[(p,y),(q,y)]);c.setFont('TNR',14);c.drawCentredString((p+q)/2,y+7,label)
            c.drawString(0,15,'HTML читается из снимка после успешной транзакции.')

def markdown(lines):
    result=[];i=0
    while i<len(lines):
        s=lines[i].strip();i+=1
        if not s:continue
        if s.startswith('#'):
            level=len(s)-len(s.lstrip('#'));title=s[level:].strip()
            if level==1 and result:result.append(PageBreak())
            result.append(Paragraph(rich(title),ST['title' if level==1 else 'h2']))
        elif s.startswith('[[diagram:'):
            kind=s[10:-2];captions={'components':'Рисунок 2.1 - Компоненты самостоятельного APK','er':'Рисунок 2.2 - Основные группы SQL-сущностей','booking':'Рисунок 2.3 - Последовательность заказа и демооплаты'}
            result.append(KeepTogether([Paragraph(rich(captions[kind]),ST['caption']),Diagram(kind)]))
        elif s.startswith('|'):
            rows=[s]
            while i<len(lines) and lines[i].strip().startswith('|'):rows.append(lines[i].strip());i+=1
            data=[]
            for row in rows:
                cells=[v.strip() for v in row.strip('|').split('|')]
                if all(re.fullmatch(r':?-+:?',v) for v in cells):continue
                data.append([Paragraph(rich(v),ST['cellhead' if not data else 'cell']) for v in cells])
            tab=Table(data,colWidths=[W*.32,W*.68],repeatRows=1,hAlign='LEFT')
            tab.setStyle(TableStyle([('GRID',(0,0),(-1,-1),.5,colors.black),('VALIGN',(0,0),(-1,-1),'TOP'),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#f2f2f2')),('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6)]));result.extend([tab,Spacer(1,12)])
        elif s.startswith('[[code:'):
            path=ROOT/s[7:-2];result.append(Paragraph(rich(str(path.relative_to(ROOT))),ST['caption']))
            for line in path.read_text(encoding='utf-8').splitlines():
                if not line.strip():result.append(Spacer(1,8));continue
                # Текст программы не уменьшается до мелкого шрифта; длинные строки переносятся.
                result.append(Paragraph(escape(line.rstrip()).replace(' ','&#160;'),ST['code']))
        elif s=='[[screenshots]]':
            labels={'search':'Поиск проживания','profile':'Личный кабинет','paid_order':'Заказ и демооплата','confirmation':'HTML-подтверждение','map':'Offline-карта','admin':'Кабинет администратора','admin_form':'Форма редактирования'}
            from PIL import Image as PILImage
            for number,(name,title) in enumerate(labels.items(),1):
                path=ROOT/'docs/screenshots'/('09-'+name+'.png')
                if not path.exists():continue
                iw,ih=PILImage.open(path).size;scale=min(W/iw,(H-75)/ih)
                result.extend([PageBreak(),Paragraph(f'Рисунок П2.{number} - '+title,ST['caption']),Image(str(path),width=iw*scale,height=ih*scale)])
        else:result.append(source_para(s) if 'URL: ' in s else Paragraph(rich(s),ST['body']))
    return result

def build(lines,path,title,cover=False):
    path.parent.mkdir(parents=True,exist_ok=True)
    doc=Document(str(path),pagesize=(PW,PH),title=title,author='Арзимуротов А.М.',leftMargin=L,rightMargin=R,topMargin=T,bottomMargin=B)
    doc.addPageTemplates(PageTemplate(id='plain',frames=Frame(L,B,W,H,leftPadding=0,rightPadding=0,topPadding=0,bottomPadding=0),onPage=footer))
    story=[]
    if cover:
        for text in ['Муромский институт (филиал) ВлГУ','Факультет информационных технологий и радиоэлектроники','Кафедра программной инженерии']:
            story.append(Paragraph(text,ST['cover']))
        story.append(Spacer(1,80))
        for text in ['КУРСОВАЯ РАБОТА','по дисциплине «Разработка приложений для мобильных операционных систем»','Мобильное приложение «Гостиница»','Пояснительная записка · версия 0.9.0']:
            story.append(Paragraph(text,ST['cover']))
        story.append(Spacer(1,65));story.append(Paragraph('Выполнил: Арзимуротов А.М., группа ПИН-123',ST['body']));story.append(Spacer(1,70));story.append(Paragraph('Муром, 2026',ST['cover']));story.append(PageBreak())
    story.append(Paragraph('Содержание',ST['title']));toc=TableOfContents();toc.levelStyles=[ST['toc'],ParagraphStyle('subtoc',parent=ST['toc'],leftIndent=14)];story.extend([toc,PageBreak()]);story.extend(markdown(lines));doc.multiBuild(story)

def main():
    lines=(ROOT/'docs/Курсовая_работа_Гостиница_0_9.md').read_text(encoding='utf-8').splitlines()
    output=ROOT/'output/pdf'
    build(lines,output/'Курсовая_работа_Гостиница_0_9.pdf','Курсовая работа «Гостиница» 0.9.0',True)
    start=next(i for i,l in enumerate(lines) if l.startswith('# 2. '));end=next(i for i,l in enumerate(lines) if l.startswith('# 3. '))
    architecture=['# 2. Разработка архитектуры системы']+lines[start+1:end]
    sources=next(i for i,l in enumerate(lines) if l=='# Список используемой литературы')
    appendix=next(i for i,l in enumerate(lines) if l.startswith('# Приложение 1'))
    architecture+=lines[sources:appendix]
    build(architecture,output/'Архитектура_системы_0_9.pdf','Архитектура самостоятельной системы 0.9.0')
    print('PDF готовы:',output)

if __name__=='__main__':main()
