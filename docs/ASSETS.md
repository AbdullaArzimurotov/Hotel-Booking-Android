# Изображения учебного каталога

Метод: **generate**, встроенный `image_gen` через skill imagegen. Изображения созданы для
этого проекта, не скачаны с Booking.com или сайтов реальных гостиниц. Логотипов, вывесок,
имен реальных объектов и водяных знаков нет. Это художественные иллюстрации вымышленных
объектов, а не доказательство существования гостиницы или услуг.

Три PNG 1672 × 941 сохранены в APK и открываются без сети. Они повторно используются
во всём демокаталоге; число гостиниц **не означает 36 уникальных фотосессий**. В разделе
мест те же изображения используются только как условные иллюстрации, что отмечено в UI.

Финальные файлы относительно корня проекта:

- `android/app/src/main/res/drawable-nodpi/hotel_exterior.png`
- `android/app/src/main/res/drawable-nodpi/hotel_room.png`
- `android/app/src/main/res/drawable-nodpi/hotel_pool.png`

Оригиналы не изменялись; в Android-ресурсы скопированы полные PNG без обрезки или ретуши.
На карточках используется `ContentScale.Crop`, в полном просмотре — `ContentScale.Fit`.

## Финальные промпты

### Внешний вид

Use case: photorealistic-natural. Asset type: bundled hotel catalogue photograph for an educational Android travel app, landscape 16:9. Create an original fictional elegant modern hotel exterior with pale limestone, warm wood, glass balconies and a lush landscaped courtyard, welcoming entry, natural golden morning sunshine. Architectural editorial photography, realistic materials, careful perspective, spacious composition, hotel clearly visible. No people, logos, signs, text or watermarks. Not any existing named hotel. High quality photograph, not a UI mockup.

### Номер

Use case: photorealistic-natural. Asset type: bundled hotel room gallery photo for an educational travel app, landscape 16:9. Original fictional premium hotel bedroom with a large neatly made bed with crisp ivory linens, warm oak furnishings, muted blue accent armchair, soft daylight through broad windows, simple warm lamps and elegant understated decor. Realistic architectural interior photography, eye-level wide composition, natural textures, inviting uncluttered scene. No people, logos, text, watermarks, no existing named hotel. Not a UI mockup.

### Бассейн

Use case: photorealistic-natural. Asset type: bundled fictional hotel resort gallery photo for an educational Android travel app, landscape 16:9. Elegant original hotel courtyard with an outdoor turquoise swimming pool, pale stone terrace, cream loungers, lush palms and greenery, subtle modern architecture in background, bright warm summer daylight. Photorealistic travel editorial photography, balanced wide composition, realistic water and materials. No people, logos, text or watermarks, not any existing named hotel, not a UI mockup.

Линейные иконки реализованы собственным `Canvas` в `TravelComponents.kt`; внешний
иконографический шрифт и сетевые сервисы для их отображения не требуются.
# Дополнения 0.9.0

Mapsforge 0.25.0 использует LGPL-3.0; OSM данные — ODbL 1.0 с атрибуцией.
Исходники шести map-фрагментов, ссылки на лицензии и рецепт конвертации находятся
в `docs/maps-source/README.md`, bbox/источник/SHA-256 — в `assets/maps/manifest.json`.
Общие OSM tile-server не скачиваются. Центральный район — ограниченная область,
не карта целой страны. Встроенные фотографии — прежние учебные иллюстрации;
импортированные фотографии выбирает пользователь и сохраняет только на своём устройстве.

## Предыдущие материалы
