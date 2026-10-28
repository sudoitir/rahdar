<div align="center">

<img src="web/public/favicon.svg" alt="" width="96" height="96">

# Rahdar

<sub>«راهدار» از «راه» + «دار» (نگه‌دارنده) ساخته شده است: نگهبانی که همهٔ راه‌هایی را که پیام‌هایتان می‌پیمایند باز و امن نگه می‌دارد.</sub>

**یک کنسول متن‌باز برای کلاسترهای Apache ActiveMQ® Artemis و Apache Kafka® شما.**

هر کلاستری که اجرا می‌کنید در یک estate است، lag به‌عنوان همان تخمینی که هست نشان داده می‌شود، پیش از هر کار مخرب پیش‌نمایش می‌بینید، و دروازهٔ تأییدی که هم انسان‌ها و هم دستیارها را نگه می‌دارد.

<a href="https://rahdar.dev/"><img src="https://img.shields.io/badge/Play%20the%20story-One%20night%20on%20call%20across%20Artemis%20and%20Kafka-0b7285?style=for-the-badge&labelColor=0b1418&logo=data:image/svg%2Bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNDAgMjQwIiByb2xlPSJpbWciIGFyaWEtbGFiZWw9IlJhaGRhciI+PHBhdGggZmlsbD0iIzBjODU5OSIgZD0iTTAgOTAgQTkwIDkwIDAgMCAxIDkwIDAgSDI0MCBWNjAgSDE1MCBBOTAgOTAgMCAwIDAgNjAgMTUwIFYyNDAgSDAgWiBNMTA1IDI0MCBWMTUwIEE0NSA0NSAwIDAgMSAxNTAgMTA1IEgyNDAgVjE1MCBBOTAgOTAgMCAwIDEgMTUwIDI0MCBIMTA1IFoiLz48L3N2Zz4K" alt="Play the story: one night on call across Artemis and Kafka" height="36"></a><br>
<sub>یک شب تعاملی کشیک · حدود ۲ دقیقه · در مرورگر خودتان اجرا می‌شود</sub>

[English](README.md) · [简体中文](README.zh.md) · **فارسی**

[![CI](https://github.com/sudoitir/rahdar/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sudoitir/rahdar/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/sudoitir/rahdar?include_prereleases&sort=semver&label=release)](https://github.com/sudoitir/rahdar/releases)
[![Docker pulls](https://img.shields.io/docker/pulls/sudoit1/rahdar?logo=docker&label=pulls)](https://hub.docker.com/r/sudoit1/rahdar)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/sudoitir/rahdar/badge)](https://scorecard.dev/viewer/?uri=github.com/sudoitir/rahdar)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=sudoitir_rahdar&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=sudoitir_rahdar)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=sudoitir_rahdar&metric=coverage)](https://sonarcloud.io/summary/new_code?id=sudoitir_rahdar)
[![Maintainability](https://sonarcloud.io/api/project_badges/measure?project=sudoitir_rahdar&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=sudoitir_rahdar)
[![Reliability](https://sonarcloud.io/api/project_badges/measure?project=sudoitir_rahdar&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=sudoitir_rahdar)
[![Security](https://sonarcloud.io/api/project_badges/measure?project=sudoitir_rahdar&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=sudoitir_rahdar)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue)](LICENSE)

[مستندات](https://rahdar.dev/fa/guide/) ·
[شروع سریع](#شروع-سریع) ·
[Kafka](https://rahdar.dev/guide/kafka) ·
[Flow](https://rahdar.dev/guide/flow) ·
[SQL Console](https://rahdar.dev/fa/guide/sql-console) ·
[MCP](https://rahdar.dev/fa/guide/mcp) ·
[گفت‌وگوها](https://github.com/sudoitir/rahdar/discussions)

</div>

> [!WARNING]
> **نسخهٔ آلفا.** پروژه در حال توسعهٔ فعال است و هنوز همهٔ قابلیت‌هایش را ندارد. ایمیج‌های منتشرشده، بیلدهای dev پیش از نسخهٔ پایدارند (`sudoit1/rahdar:dev`؛ هنوز `:latest` نداریم). منتظر تغییرات ناسازگار باشید.

![Rahdar: توپولوژی، جدول صف‌های همهٔ نودها، صف dead-letter، مسیر پیام‌ها و نمودارها](docs/img/demo.gif)

<div dir="rtl" align="right">

## شروع سریع

Rahdar و Postgres آن، از روی ایمیج منتشرشده. بروکرهایتان همان‌طور که هستند می‌مانند: آن‌ها را در رابط کاربری ثبت می‌کنید و هیچ‌کدام از این دستورها بروکری راه نمی‌اندازد.

</div>

```bash
base=https://raw.githubusercontent.com/sudoitir/rahdar/main/deploy/compose
mkdir -p rahdar/compose rahdar/postgres && cd rahdar/compose   # the compose file mounts ../postgres
curl -sO "$base/compose.prod.yaml"
for f in postgresql.conf 10-pg-stat-statements.sql; do curl -s "${base%/compose}/postgres/$f" -o "../postgres/$f"; done
curl -s "$base/.env.example" | sed \
  -e "s|^SECRET_KEY=.*|SECRET_KEY=$(openssl rand -base64 32)|" \
  -e "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -hex 24)|" > .env
docker compose -f compose.prod.yaml --env-file .env up -d
docker compose -f compose.prod.yaml logs rahdar | grep -A4 'Created administrator'
```

<div dir="rtl" align="right">

سپس <http://localhost:8080> را باز کنید و با کاربر `admin` و رمزی که خط آخر چاپ می‌کند وارد شوید. این رمز فقط یک بار نشان داده می‌شود و در اولین ورود، رمز دلخواه خودتان را انتخاب و تأیید دومرحله‌ای‌ای را که نقش admin می‌خواهد راه‌اندازی می‌کنید.

</div>

<details>
<summary dir="rtl">از روی کلون مخزن با <code>just</code>، یا یک کانتینر تنها روی Postgres خودتان</summary>

```bash
git clone https://github.com/sudoitir/rahdar && cd rahdar
just up          # دو replica پشت HAProxy، قفل‌شده روی آخرین نسخه (just up-single: یک replica)
```

```bash
docker run -p 8080:8080 \
  -e RAHDAR_DB_URL=jdbc:postgresql://db:5432/rahdar \
  -e RAHDAR_DB_USER=rahdar \
  -e RAHDAR_DB_PASSWORD=... \
  -e RAHDAR_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/rahdar:dev
```

</details>

<div dir="rtl" align="right">

همهٔ متغیرها، تنظیمی که reverse proxy برای جریان SSE لازم دارد و راه بازیابی اولین ورودِ ازدست‌رفته در [راهنمای پیکربندی](https://rahdar.dev/fa/guide/configuration) آمده است.

## چرا

ساعت ۰۲:۵۱ است و دو چیز هم‌زمان شما را بیدار می‌کنند: یک consumer group در Kafka روی `payments` دارد عقب می‌افتد و `ORDERS.DLQ` جایی روی یک جفت Artemis دارد پر می‌شود. بدون Rahdar یعنی یک تب کنسول مدیریت برای هر بروکر Artemis، `kafka-consumer-groups.sh` و `kafka-transactions.sh` در ترمینال، و حساب‌وکتاب خودتان در میانهٔ آن‌ها.

Rahdar **هر کلاستری را که اجرا می‌کنید در یک estate** می‌گذارد، بروکرش هر چه باشد. سلامت روی یک مقیاس سنجیده می‌شود و همهٔ backlogها در یک جدول‌اند، بدترین اول: صف‌های Artemis و groupهای Kafka کنار هم، هر کدام با نام خودش. هر عدد می‌گوید چقدر مطمئن است: عمق یک صف Artemis دقیق است، lag یک group در Kafka تخمین است و تفکیک هر partition را همراه دارد، و عددی که Rahdar نتواند بخواند همین را می‌گوید، نه اینکه صفر نشان بدهد. هیچ کار مخربی پیش از آن‌که ببینید چه می‌کند اجرا نمی‌شود، و جایی که دروازهٔ تأیید روشن است، نفر دوم باید تأییدش کند.

<a href="https://rahdar.dev/"><img src="docs/img/story.webp" width="1600" height="900" alt="داستان ساعت ۰۲:۵۱ با دو هشدار شروع می‌شود: یک consumer group در Kafka روی payments عقب می‌افتد و ORDERS.DLQ روی یک جفت Artemis بالا می‌رود. آن را در مرورگرتان بازی کنید."></a>

**[▶ داستان را بازی کنید (انگلیسی)](https://rahdar.dev/)**: تراکنش معلقِ پشت lag را پیدا کنید و abort کنید، یک group را با تأییدِ نگه‌داشته reset کنید و صف dead-letter در Artemis را پاک کنید، همه در یک estate.

## چه کارهایی می‌کند

</div>

| توپولوژی کلاستر | صف‌های همهٔ نودها |
|---|---|
| [![توپولوژی live/backup همراه با وضعیت replication و محور NodeID مشترک](docs/img/topology.png)](docs/img/topology.png) | [![همهٔ صف‌های همهٔ نودها در یک جدول مجازی](docs/img/queues.png)](docs/img/queues.png) |
| **کلاینت‌ها و مسیر پیام** | **SQL Console** |
| [![Flow: نقطه‌های متحرک نرخ هر مسیر را نشان می‌دهند و با رفتن نشانگر روی یک صف، کل مسیرش برجسته می‌ماند](docs/img/flow.gif)](docs/img/flow.gif) | [![SQL Console: کوئری روی همهٔ صف‌های کلاستر، با تعیین هزینه پیش از اجرا و سپس tail زنده](docs/img/sql-console.gif)](docs/img/sql-console.gif) |
| **پلاگین‌ها** | **تنظیمات** |
| [![نصب یک پلاگین: کارهایی که اجازه دارد انجام دهد و SQL تغییرات پایگاه‌داده‌اش، پیش از تأیید با نگه‌داشتن دکمه بررسی می‌شوند](docs/img/plugin-install.gif)](docs/img/plugin-install.gif) | [![تنظیمات: ترجیحات نمایش، پیکربندی عملیاتی Rahdar، و بخش‌های این کلاستر و هر پلاگین](docs/img/settings.png)](docs/img/settings.png) |

<div dir="rtl" align="right">

Rahdar از طریق [OpenBroker](https://github.com/sudoitir/openbroker)، یک SDK متن‌باز بروکر با یک درایور برای هر سیستم، با بروکرها صحبت می‌کند. صفحه‌هایی که همهٔ سیستم‌ها دارند (estate، subscriptionها و destinationها، مرور پیام‌ها، purge، گزارش ممیزی، API و MCP) روی هر کدام و با واژگان خودِ همان سیستم کار می‌کنند، و کاری که یک سیستم نتواند انجام دهد دیدنی می‌ماند، غیرفعال و همراه با دلیل.

</div>

| سیستم | پشتیبانی‌شده | راهنما |
| --- | --- | --- |
| Apache ActiveMQ® Artemis | نسخهٔ ۲٫۳۳٫۰ و بالاتر | [راهنما](https://rahdar.dev/fa/guide/) |
| Apache Kafka® | نسخهٔ ۳٫۰ و بالاتر، در حالت KRaft و ZooKeeper | [Kafka](https://rahdar.dev/guide/kafka) |
| RabbitMQ | به‌زودی؛ هنوز پشتیبانی نمی‌شود | |

<div dir="rtl" align="right">

جزئیات و آنچه بیرون از این بازه‌ها رخ می‌دهد: [نسخه‌های پشتیبانی‌شدهٔ بروکر](https://rahdar.dev/guide/supported-versions).

### Apache Kafka

- **[Consumer groupها](https://rahdar.dev/guide/kafka#consumer-groups)**: groupهای classic، consumer و share همراه با وضعیت، اعضا و lag. lag یک تخمین است و همین‌طور علامت‌گذاری می‌شود: بازش کنید تا همهٔ partitionها را ببینید، کندترین اول، با offset ثبت‌شده، انتهای لاگ و سن نمونه.
- **[Reset کردن offsetها](https://rahdar.dev/guide/kafka#reset-offsets)**: به earliest، latest، یک زمان یا یک offset، با پیش‌نمایش برای هر partition از آنچه group رد می‌کند یا دوباره می‌خواند. groupی که عضو دارد، با نام اعضا، رد می‌شود؛ reset روی همان وضعیتی اجرا می‌شود که پیش‌نمایش شد، با نگه‌داشتن دکمه تأیید می‌شود، جایی که دروازه روشن است منتظر تأیید می‌ماند، و با offsetهای پیش و پس از آن ممیزی می‌شود.
- **[پیکربندی topic](https://rahdar.dev/guide/kafka#topic-configuration)**: هر تنظیم همراه با اینکه مقدارش از کجا می‌آید، با تغییر از راه یک بازبینی که بروکر پیش از اعمال هر چیزی اعتبارسنجی‌اش می‌کند.
- **[ACLها](https://rahdar.dev/guide/kafka#acls)**: فهرست، افزودن و حذف، همراه با دسترسی مؤثر هر principal پیش و پس از تغییر.
- **[تراکنش‌ها](https://rahdar.dev/guide/kafka#transactions)**: تراکنش معلقی که consumerهای read-committed را عقب نگه می‌دارد علامت می‌خورد، و abort کردنش پیش‌نمایش دارد، با نگه‌داشتن دکمه تأیید و ممیزی می‌شود.
- **[بروکرها و quorum](https://rahdar.dev/guide/kafka#brokers-and-the-quorum)**: هر بروکر، اینکه Rahdar از آدرس advertise‌شده‌اش به آن رسیده یا نه، و quorum کنترلرهای KRaft.
- **روی صفحه‌های مشترک**: یک topic را بر اساس partition و offset یا زمان مرور کنید، بدون پیوستن به group؛ رکوردهای نخوانده یک group را purge کنید یا یک topic را truncate کنید، با گفتن دامنهٔ اثر، از جمله هر groupی که رکوردهای نخوانده‌اش را از دست می‌دهد.

### Apache Artemis

- **توپولوژی**: جفت‌های live/backup و وضعیت replication آن‌ها. نقش HA در هر چرخه از تک‌تک نودها poll می‌شود و هرگز از پیکربندی خوانده نمی‌شود؛ اگر در یک جفت دو نود هم‌زمان live باشند، هشدار split-brain داده می‌شود.
- **[Flow](https://rahdar.dev/guide/flow)**: کدام اپلیکیشن به کدام آدرس پیام می‌فرستد، آن آدرس از مسیر divertها، bridgeها و پرش‌های کلاستر به کدام صف‌ها می‌رسد، و چه کسی با چه نرخی آن‌ها را مصرف می‌کند. مشکلاتی مثل انباشت پیام بدون مصرف‌کننده با کلمات روشن گفته می‌شوند، و از کلاینت‌ها فقط تا وقتی کسی صفحه را نگاه می‌کند نمونه‌برداری می‌شود.
- **[SQL Console](https://rahdar.dev/fa/guide/sql-console)**: Artemis نمی‌تواند بگوید *سفارش ۴۴۷۱ کجا رفت*. تنها فیلتر سمت سرورش JMS selector است: فقط هدرها را می‌بیند و هیچ‌وقت بدنهٔ پیام را نه، آن هم هر بار فقط یک صف روی یک نود. Rahdar از پسش برمی‌آید:

</div>

```sql
SELECT * FROM "ORDER.*"
WHERE body->>'orderId' = '4471'
LIMIT 50
```

<div dir="rtl" align="right">

زبان کوئری فقط `SELECT` فقط‌خواندنی است و با فهرست ثابتی از ستون‌ها سنجیده می‌شود، پس هیچ کوئری‌ای نمی‌تواند چیزی را تغییر دهد. شرط‌های روی هدر به JMS selector تبدیل می‌شوند و هزینه‌ای ندارند؛ شرط‌های روی بدنه نیاز به پیمایش دارند، و **نوار plan پیش از اجرای کوئری می‌گوید کدام حالت است**. کوئری‌ای که از سقف هزینه بگذرد همراه با برآوردش رد می‌شود، نه اینکه بی‌صدا نتیجه‌اش بریده شود. می‌توانید tail زنده اضافه کنید یا برای یک صف [capture کامل](https://rahdar.dev/fa/guide/message-capture) را روشن کنید: یک کپی با ظرفیت محدود روی خود بروکر که Rahdar آن را تخلیه می‌کند، تا حتی پیامی که بین دو poll مصرف شده هم قابل پیدا کردن باشد.

- **منابع همهٔ نودها**: صف‌ها، آدرس‌ها، مصرف‌کننده‌ها، نشست‌ها، اتصال‌ها و تولیدکننده‌ها در یک جدول مجازی؛ هر ردیف نشان می‌دهد مال کدام نود است و از طریق SSE به‌روز می‌ماند.
- **عملیات روی پیام**: مرور، ارسال، جابه‌جایی، retry، منقضی‌کردن، حذف و purge. هر فراخوانی تغییردهنده `?dryRun=true` را می‌پذیرد، عملیات گروهی در سمت سرور سقف دارند و نتیجه به تفکیک نود گزارش می‌شود.
- **ردیابی request-reply**: درخواست‌ها در همهٔ آدرس‌ها و نودها به پاسخ‌هایشان وصل می‌شوند و آمار تأخیر و timeout با انتظاراتی که تعریف کرده‌اید سنجیده می‌شود.
- **[پیکربندی بروکر](https://rahdar.dev/fa/guide/broker-configuration)**: address settingها، security settingها، divertها، bridgeها و صف‌هایی را که کلاستر باید داشته باشد تعریف کنید و مسیریابی بینشان را روی یک بوم بچینید. آن‌ها را اول روی یک نود قناری اعمال کنید، در حالی که پیش از هر نوشتنی همهٔ خطرها گفته می‌شود، یا به‌صورت بخشی از `broker.xml` خروجی بگیرید و ببینید هر نود کجا از تعریف فاصله گرفته است. در اولین اجرا، Rahdar وضعیت فعلی کلاستر را به‌عنوان نسخهٔ ۱ پیشنهاد می‌دهد؛ بی‌اجازهٔ شما چیزی پذیرفته نمی‌شود، و هیچ bridgeای هرگز پذیرفته نمی‌شود، چون بروکر فقط بخشی از آن را گزارش می‌کند.
- **[بازبینی راه‌اندازی](https://rahdar.dev/guide/setup-review)**: پیکربندی HA، کلاسترینگ، ماندگاری و ایمنی پیام در هر کلاستر با اشتباه‌های شناخته‌شده سنجیده می‌شود: یک جفت replication تنها که نمی‌تواند در رأی‌گیری quorum برنده شود، redistribution که روی `-1` مانده، connectorی که `localhost` را اعلام می‌کند، نبودِ آدرس dead-letter. هر یافته شواهدش را به تفکیک نود و اصلاحیهٔ `broker.xml` را همراه دارد، و می‌توان آن را با ذکر دلیل و به‌صورت ثبت‌شده، به‌عنوان ریسکی شناخته‌شده پذیرفت.
- **[ارسال هشدار](https://rahdar.dev/guide/alert-delivery)**: Slack، Microsoft Teams، PagerDuty (incidentها همراه با هشدار باز و بسته می‌شوند)، ایمیل و webhookهای امضاشده؛ هرکدام پیش از ذخیره آزمایش می‌شود و گزارش ارسال می‌گوید چرا یک اعلان نرسیده است.
- **[حاکمیت داده](https://rahdar.dev/guide/data-governance)**: هدرها و propertyهای حساس پوشانده می‌شوند، اطلاعات شخصی (PII) خودکار دسته‌بندی می‌شود و پوشاندنشان به نقش بیننده بستگی دارد.
- **حاکمیت**: احراز هویت در همه‌جا، نقش‌ها و مجوزها در سه سطح سراسری ← محیط ← کلاستر، توکن‌های API، OIDC/SSO اختیاری، و گزارش ممیزی‌ای که هر تغییر و نتیجه‌اش را ثبت می‌کند.
- **[سرور MCP](https://rahdar.dev/fa/guide/mcp)**: همین قابلیت‌ها برای دستیار هوش مصنوعی، با همان مجوزها و همان گزارش ممیزی. هشت ابزار `kafka_*` آن چیزی را تغییر نمی‌دهند که پیش‌تر پیش‌نمایش داده نشده باشد، و هرجا دروازهٔ تأیید روشن است منتظر یک انسان می‌مانند.
- **[پلاگین‌ها](https://rahdar.dev/guide/plugins)**: فایل `.jar` یک پلاگین را از رابط کاربری نصب کنید؛ با صفحه‌ها، API، ابزارهای دستیار و داده‌های مخصوص خودش. پیش از تأیید، هر کاری که می‌تواند بکند نشان داده می‌شود و جز در صورت نیاز، راه‌اندازی مجدد لازم نیست. به‌روزرسانی‌ها نشان می‌دهند چه چیزی عوض می‌شود و قابل بازگشت‌اند؛ پلاگینی که خطا بدهد هرگز Rahdar را از کار نمی‌اندازد. برای شروع یک پلاگین از [قالب آماده](examples/plugin-template) استفاده کنید.

## چطور کار می‌کند

چهار قاعده در سراسر محصول برقرار است:

- **از پایه، هوای بروکر را دارد.** خواندن‌ها دسته‌ای است (در Artemis، برای هر نود یک POST به Jolokia، هرگز یکی برای هر صف)، polling لایه‌بندی شده و برای هر نود محدودیت نرخ وجود دارد. Rahdar هرگز نباید دلیل از پا افتادن یک بروکر باشد.
- **پیش‌فرض، ایمنی است.** هر فراخوانی مخرب را می‌شود اول به‌صورت dry run اجرا کرد و دامنهٔ اثرش را می‌گوید، و purge، حذف، truncate و resetها با نگه‌داشتن دکمه‌ای که تعداد را نام می‌برد تأیید می‌شوند.
- **وضعیت از بروکرها می‌آید، نه از پیکربندی.** نقش‌های HA در Artemis از تک‌تک نودها poll می‌شود، و سلامت یک کلاستر Kafka از بروکرها و quorumش ساخته می‌شود.
- **صداقت دربارهٔ قابلیت‌ها.** اگر قابلیتی در دسترس نباشد، همین را می‌گوید و دقیقاً همان `broker.xml` لازم برای فعال‌کردنش را نشان می‌دهد. هیچ‌چیز بی‌صدا ناپدید نمی‌شود.

Java 25 · Spring Boot 4.1 · PostgreSQL با Liquibase · React 19 + Vite + Mantine 9 · TanStack Router/Query/Table · React Flow · SDK [OpenBroker](https://github.com/sudoitir/openbroker) با درایورهای Artemis و Kafka · SSE · یک ایمیج کانتینر. [معماری](https://rahdar.dev/reference/architecture) و [همهٔ تصمیم‌های ثبت‌شده](https://rahdar.dev/reference/adr/) را بخوانید.

## مشارکت

پرسش‌ها و ایده‌ها را در [Discussions](https://github.com/sudoitir/rahdar/discussions)، باگ‌ها را در [issues](https://github.com/sudoitir/rahdar/issues/new/choose) و آسیب‌پذیری‌ها را از طریق [گزارش خصوصی](https://github.com/sudoitir/rahdar/security/advisories/new) ([SECURITY.md](SECURITY.md)) مطرح کنید. برای بیلد به JDK 25، Node 24، Docker و [`just`](https://github.com/casey/just#packages) نیاز دارید؛ یک dev container هم آماده است.

</div>

```bash
just dev-up          # Postgres + یک جفت واقعی primary/backup از Artemis + یک نود Kafka + Rahdar، از روی سورس
just dev             # یا: بک‌اند :8080 و Vite :5173 با هم، همراه با live reload
just verify          # همهٔ بررسی‌های CI
```

<div dir="rtl" align="right">

فرمان `ADMIN_PASSWORD=… just demo` (اجرای اول به `NEW_ADMIN_PASSWORD=…` و اجراهای بعدی به `ADMIN_TOTP_SECRET=…` هم نیاز دارند) دو جفت live/backup دیگر اضافه می‌کند و هر شش نود را با ترافیکی شبیه محیط واقعی پر می‌کند: divertها، یک bridge، پرش‌های کلاستر، صفی بدون مصرف‌کننده که مدام پرتر می‌شود، انباشت واقعی dead-letter و یک نود متوقف. همچنین یک کلاستر Kafka سه‌بروکره به نام `events` ثبت می‌کند، با groupهایی که عقب نمی‌مانند و groupهایی که عقب می‌افتند، یک topic فشرده‌شده (compacted)، ACLها و یک تراکنش معلق. تصاویر بالا با `just shots` و `just demo-gif` از همین استک ضبط شده‌اند و هیچ‌چیزشان صحنه‌سازی نیست.

هر قابلیت از فرایند **OpenSpec** می‌گذرد و تصمیم‌های مهم به‌صورت [**ADR**](docs/adr/) ثبت می‌شوند. از [`CONTRIBUTING.md`](CONTRIBUTING.md) شروع کنید.

## انتشار نسخه‌ها

هر merge که اپلیکیشن را تغییر دهد یک نسخهٔ جدید منتشر می‌کند. شمارهٔ نسخه به شکل CalVer (`YYYY.MM.PATCH`) است و روی Docker Hub سه تگ می‌گیرد: `2026.09.3` (تغییرناپذیر)، `2026.09` (همان ماه) و `dev` (آخرین نسخه). تا اولین نسخهٔ پایدار، تگ `:latest` نداریم. فایل jar قابل‌اجرا همراه `.sha256` به هر نسخه پیوست می‌شود و یادداشت‌های انتشار از پیام‌های commit ساخته می‌شوند ([GitHub Releases](https://github.com/sudoitir/rahdar/releases)). API پلاگین (Maven Central) و SDK (npm) همراه نسخه‌هایی منتشر می‌شوند که تغییرشان می‌دهند.

## مجوز

[Apache-2.0](LICENSE)؛ همان مجوزی که خود Artemis و Kafka دارند.

Apache، Apache Kafka، Kafka، Apache ActiveMQ، ActiveMQ و Artemis نشان‌های تجاری بنیاد نرم‌افزار Apache هستند؛ RabbitMQ نشان تجاری مالک آن است. Rahdar به آن‌ها وابسته نیست و از سوی آن‌ها تأیید نشده است. این نام‌ها فقط برای توصیف بروکرهایی به کار رفته‌اند که Rahdar با آن‌ها کار می‌کند.

</div>

---

<div align="center">

اگر در وقتتان صرفه‌جویی کرد، با یک ⭐ به دیده‌شدنش کمک کنید.

</div>
