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

---

## 1) البناء (بدون Android Studio)
1. ارفع المجلد لريبو GitHub (private عادي).
2. GitHub Actions يبني الـ APK تلقائياً. تلقاه بـ **Actions ← آخر run ← Artifacts ← ha-wall-apk**.
3. **مهم للتحديثات:** بدون مفتاح توقيع ثابت، كل build ينوقّع بمفتاح debug جديد، والتحديث ما ينثبت فوك القديم. سوّي مفتاح مرة وحدة:
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias hawall -keyalg RSA -keysize 2048 -validity 36500
   base64 -w0 release.jks   # انسخ الناتج
   ```
   وبعدين ضيف بالريبو **Settings ← Secrets ← Actions**:
   `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, و`KEY_ALIAS=hawall`.
   لا ترفع ملف `.jks` نفسه للريبو.

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
```

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

## 4) أتمتة حماية البطارية (مقترحة)
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
