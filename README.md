# HA Wall — لانشر Home Assistant للتابلت الحائطي (Fire)

تطبيق أندرويد خفيف يفتح داشبورد Home Assistant بمحرك **GeckoView** (فايرفوكس) المدمج بداخله، فما يعتمد على WebView مالت Fire OS القديم. ويسجّل التابلت بـ HA كجهاز **Mobile App** ويبعث سنسرات البطارية.

## شنو يسوي
- Kiosk بشاشة كاملة، وزر Back ما يطلعك من التطبيق.
- إذا فشل التحميل يعيد المحاولة تلقائياً (5 ثواني تتضاعف لحد دقيقة)، وأول ما يرجع النت يعيد التحميل فوراً.
- إذا مات content process مال Gecko (مثلاً من نقص الرام) يبني الجلسة من جديد.
- Reload كامل كل N ساعة (الافتراضي 12) حتى ينظف الذاكرة.
- يشتغل وي تشغيل التابلت، ومسجّل كـ Home launcher.
- Foreground service يمسك Wi-Fi lock حتى ما ينطفي الواي فاي، ويبعث السنسرات:
  - فوراً إذا تغيّر الشحن أو الشاحن أو نسبة البطارية
  - وكل 5 دقايق heartbeat
  - وبعد ما يرجع النت

### السنسرات اللي تطلع بـ HA
| Entity | النوع |
|---|---|
| Battery Level | `%`، device_class battery |
| Battery State | charging / discharging / full / not_charging |
| Is Charging | binary_sensor، device_class battery_charging |
| Charger Type | ac / usb / wireless / none |
| Battery Temperature | °C |
| Battery Health | good / overheat / … |
| WiFi Signal Strength | dBm (مفيد تشخّص منه الفصل) |
| Motion | binary_sensor motion، من كاميرا التابلت الأمامية (attribute `camera` يبين حالة الكاميرا) |
| Screen On | binary_sensor، هل الشاشة شغالة |
| Command Channel | connected / disconnected، حالة استقبال الأوامر من HA |

---

## 1) البناء (بدون Android Studio)
1. ارفع المجلد لريبو GitHub (private عادي).
2. GitHub Actions يبني الـ APK تلقائياً. تلقاه بـ **Actions ← آخر run ← Artifacts ← ha-wall-apk**.
3. **التوقيع:** مفتاح التوقيع موجود بـ `keystore/hawall-release.jks` (الريبو private). ضيف باسورده كـ secret باسم `KEYSTORE_PASSWORD` من **Settings ← Secrets and variables ← Actions**. بدونه، كل build ينوقّع بمفتاح debug مؤقت، والتحديث ما ينثبت فوك النسخة القديمة.

> أو افتحه بـ Android Studio وسوّي Build ← Generate APK.

### نسخة GeckoView
موجودة بـ `gradle.properties`:
- `+` (الافتراضي): آخر نسخة release. تحتاج أندرويد 8 أو أعلى، يعني Fire OS 7 أو 8 مثل Fire HD 10 (2021).
- `143.+`: للأجهزة القديمة على Fire OS 5 أو 6، لأن فايرفوكس من 144 وطالع يحتاج أندرويد 8 كحد أدنى. الـ minSdk يتعدل تلقائياً.

---

## 2) التثبيت على التابلت
فعّل **Developer options ← ADB** على التابلت، وبعدين:

```bash
adb install -r ha-wall.apk

# يخلي التطبيق يقدر يفتح نفسه بعد الـ boot (ضروري على Fire OS 8 / أندرويد 10+)
adb shell appops set com.taqi.hawall SYSTEM_ALERT_WINDOW allow

# يطلّعه من Doze حتى ما ينقتل ولا يوقف الواي فاي
adb shell dumpsys deviceidle whitelist +com.taqi.hawall

# الواي فاي ما ينام أبداً (على الأنظمة القديمة)
adb shell settings put global wifi_sleep_policy 2

# إطفاء الشاشة الحقيقي (Device Admin). أو فعّله من زر بشاشة الإعدادات
adb shell dpm set-active-admin com.taqi.hawall/.AdminReceiver

# صلاحية الكاميرا لكشف الحركة. أو وافق عليها لما تطلع بأول تشغيل
adb shell pm grant com.taqi.hawall android.permission.CAMERA
```
> ⚠️ ما تكدر تمسح التطبيق وهو Device Admin. لازم تطفيه أول من **Settings ← Security ← Device admin apps**، أو بالأمر:
> `adb shell dpm remove-active-admin com.taqi.hawall/.AdminReceiver`

**مهم:** لا تخلي PIN أو باسورد على شاشة القفل. التطبيق يطلع فوك شاشة القفل ويتجاوزها، بس الـ PIN يمنع هذا.

### خلّيه الـ Home launcher (اختياري)
Fire OS ما يخليك تختار launcher ثاني من الإعدادات. التطبيق أصلاً يفتح وي الـ boot، فهذي الخطوة مو ضرورية. إذا تريده بدال لانشر أمازون بالكامل:
```bash
adb shell pm disable-user --user 0 com.amazon.firelauncher
# للرجوع:
adb shell pm enable com.amazon.firelauncher
```
⚠️ خلي ADB مفعّل قبل ما تسويها. إذا صار شي بالتطبيق، هو طريقك الوحيد للرجوع.

---

## 3) الإعداد
1. بـ HA روح **Profile ← Security ← Long-lived access tokens** وسوّي توكن.
2. أول تشغيل تطلعلك شاشة الإعدادات:
   - **URL:** استخدم العنوان المحلي `http://192.168.x.x:8123`، أسرع وما يمر بالـ Cloudflare Tunnel.
   - **Dashboard path:** مثلاً `/dashboard-wall/0`.
   - **Token:** التوكن اللي سويته.
   - **Device name:** مثلاً `Wall Tablet`.
3. دوس **Test sensors**، لازم يطلع الجهاز بـ **Settings ← Devices ← Mobile App**.
4. **Save & open** وسجّل دخول HA مرة وحدة بالصفحة. الجلسة تنحفظ.

**ترجع للإعدادات:** دوس 5 مرات خلال 3 ثواني على الزاوية **اليمين التحتانية** للشاشة.

> التوكن يُستخدم بس بالتسجيل الأول. بعدها كل التحديثات تروح عن طريق webhook الجهاز، مثل تطبيق HA الرسمي بالضبط.
> إذا مسحت الجهاز من HA، التطبيق يحس (HTTP 410) ويسجّل نفسه من جديد بالتحديث الجاي.

---

## 4) الحركة والشاشة
- الكاميرا الأمامية تشتغل بدقة واطية حوالي 4 فريمات بالثانية، ومحد يشوفها ولا تنحفظ أي صورة. التطبيق يقارن الفريمات حتى يكشف الحركة.
- إذا صارت حركة: تشتغل الشاشة، و`binary_sensor.wall_tablet_motion` يصير `on`، ويرجع `off` بعد 30 ثانية بدون حركة.
- إذا مرت المدة المحددة بدون حركة ولا لمس (الافتراضي 120 ثانية)، تنطفي الشاشة **إطفاء حقيقي** بـ `lockNow`. إذا Device Admin مو مفعّل، تتعتّم لحد السواد بدال الإطفاء.
- الإعدادات (الحساسية، المدة، تشغيل الكاميرا وإطفائها) تلقاها بشاشة الإعدادات. وبيها زر **"Test: screen off now"** تجرب بيه.

> **إذا الكاميرا ما تكشف حركة والشاشة مطفية:** بعض الأجهزة توقف الكاميرا مع إطفاء الشاشة. تابع الـ attribute `camera` مال سنسر Motion: إذا يطلع `error` أو `disconnected` بعد الإطفاء، شيل علامة "Real screen-off" من الإعدادات وخليها تتعتّم. التعتيم يخلي الكاميرا شغالة دائماً.
> وبالظلام الكامل الكاميرا ما تشوف شي، فالإضاءة الخافتة لازمة.

### أوامر من Home Assistant
التطبيق يستقبل أوامر من `notify.mobile_app_wall_tablet` (الاسم يتبع اسم الجهاز):

```yaml
# شغّل الشاشة (مثلاً من جرس الباب أو حساس الباب)
service: notify.mobile_app_wall_tablet
data:
  message: command_screen_on

# اطفي الشاشة
service: notify.mobile_app_wall_tablet
data:
  message: command_screen_off

# السطوع 0-255، أو -1 حتى يرجع لإعداد النظام
service: notify.mobile_app_wall_tablet
data:
  message: command_brightness
  data:
    level: 40

# افتح صفحة معينة (مثلاً الكاميرات وقت يدك الجرس)
service: notify.mobile_app_wall_tablet
data:
  message: command_url
  data:
    url: /lovelace/cameras

# اعمل reload للصفحة
service: notify.mobile_app_wall_tablet
data:
  message: command_reload

# أي رسالة ثانية تشغّل الشاشة وتطلع كتنبيه على الشاشة
service: notify.mobile_app_wall_tablet
data:
  title: الباب
  message: أكو أحد بالباب
```
> إذا ما طلع `notify.mobile_app_wall_tablet` بعد التحديث، دوس **Re-register device in HA** بالإعدادات.

مثال: الإضاءة تشتغل من حركة التابلت:
```yaml
alias: Hall light from tablet motion
trigger:
  - platform: state
    entity_id: binary_sensor.wall_tablet_motion
    to: "on"
condition:
  - condition: sun
    after: sunset
action:
  - service: light.turn_on
    target: { entity_id: light.hall }
```

---

## 5) أتمتة حماية البطارية (مقترحة)
التابلت المعلّق ومربوط شحن 24 ساعة، البطارية مالته تنفخ. ركّب له سوكت ذكي وخلي HA يشحنه بين 20 و80٪:

```yaml
alias: Wall tablet battery 20-80
mode: single
trigger:
  - platform: numeric_state
    entity_id: sensor.wall_tablet_battery_level
    below: 20
    id: "low"
  - platform: numeric_state
    entity_id: sensor.wall_tablet_battery_level
    above: 80
    id: "high"
action:
  - choose:
      - conditions: [{ condition: trigger, id: "low" }]
        sequence: [{ service: switch.turn_on, target: { entity_id: switch.tablet_plug } }]
      - conditions: [{ condition: trigger, id: "high" }]
        sequence: [{ service: switch.turn_off, target: { entity_id: switch.tablet_plug } }]
```
غيّر أسماء الـ entities حسب اسم الجهاز والسوكت عندك. وتكدر تضيف تنبيه إذا `binary_sensor.wall_tablet_is_charging` صار off وانت ما طافيه، لأن هذا معناه الشاحن انفصل.

---

## ملاحظات
- النوافذ مثل `alert()` و`confirm()` ما تطلع حالياً، لأن ماكو PromptDelegate. لوحات HA العادية ما تحتاجها.
- الحجم تقريباً 60–80MB، لأن محرك Gecko مضمّن للمعماريتين.
- التطبيق ماخذ wake lock جزئي حتى يضمن الـ heartbeat والشاشة مطفية. هذا يصرف شوية بطارية زيادة بفترات عدم الشحن.
- الكاميرا المشتغلة بشكل مستمر تصرف طاقة وتسخّن الجهاز شوية. لهذا خلي الشحن على أتمتة 20–80٪ اللي فوك.
