---
layout: home
title: Rahdar
titleTemplate: یک کنسول برای کلاسترهای Artemis و Kafka شما
head:
  - - meta
    - name: description
      content: کنسول متن‌باز عملیات برای Apache ActiveMQ Artemis و Apache Kafka؛ همهٔ کلاسترها در یک estate، lag صادقانه به‌عنوان تخمین، پیش‌نمایش پیش از هر کار مخرب، و دروازهٔ تأیید برای انسان و دستیار.

hero:
  name: Rahdar
  text: یک کنسول برای کلاسترهای Artemis و Kafka شما
  tagline: کنسول متن‌باز عملیات برای بروکرهای پیام. هر کلاستری که اجرا می‌کنید در یک estate است، هر عددی می‌گوید چقدر مطمئن است، و هیچ کار مخربی پیش از آن‌که ببینید چه می‌کند اجرا نمی‌شود.
  actions:
    - theme: brand
      text: شروع سریع
      link: /fa/guide/quickstart
    - theme: alt
      text: داستان تعاملی (انگلیسی)
      link: https://rahdar.dev/
    - theme: alt
      text: GitHub
      link: https://github.com/sudoitir/rahdar

features:
  - title: یک estate برای همهٔ سیستم‌ها
    details: کلاسترهای Artemis و Kafka کنار هم، با سلامت سنجیده‌شده روی یک مقیاس. صف‌های Artemis و consumer groupهای Kafka در یک جدول، مرتب‌شده بر اساس backlog، هر کدام با نام خودِ سیستمش.
    link: /fa/guide/
  - title: lag صادقانه
    details: lag در Kafka یک تخمین است و همین را می‌گوید. بازش کنید تا تفکیک هر partition را ببینید، کندترین اول، با سن نمونه. عددی که خوانده نمی‌شود «در دسترس نیست» نوشته می‌شود، با دلیلش، و هرگز صفر نشان داده نمی‌شود.
    link: /guide/kafka
  - title: اول پیش‌نمایش، بعد اجرا
    details: هر فراخوانی مخرب dry run دارد و دامنه‌اش را می‌گوید. reset کردن offsetها برای هر partition نشان می‌دهد چند رکورد رد یا دوباره خوانده می‌شود؛ گروهی که هنوز عضو دارد، با نام اعضا، رد می‌شود. تأیید با نگه‌داشتن دکمه تا پر شدن است.
    link: /guide/kafka#reset-offsets
  - title: دروازهٔ تأیید
    details: وقتی دروازهٔ تأیید روشن است، reset، truncate و abort یک تراکنش منتظر تأیید نفر دوم می‌مانند و هنگام اجرا وضعیت دوباره بررسی می‌شود. هر تغییر پیش از فراخوانی بروکر ممیزی می‌شود.
    link: /guide/kafka
  - title: نگاه کلاستری به Artemis
    details: توپولوژی live/backup که نقش HA را در هر چرخه از هر نود poll می‌کند و هرگز از پیکربندی نمی‌خواند. همهٔ صف‌های همهٔ نودها در یک جدول، به‌همراه Flow، SQL روی پیام‌ها و پیکربندی اعلانی بروکر.
    link: /fa/guide/sql-console
  - title: یک سرور MCP با همان دسترسی‌ها
    details: دستیار با کلید API‌ای که هرگز از دسترسی صاحبش فراتر نمی‌رود کلاسترهای واقعی شما را می‌خواند. تغییرهای هشت ابزار kafka_* فقط روی پیش‌نمایشی که دیده‌اند اجرا می‌شوند و وقتی دروازه روشن است منتظر تأیید یک انسان می‌مانند.
    link: /fa/guide/mcp
---

## سیستم‌های پشتیبانی‌شده

- **Apache ActiveMQ Artemis** نسخهٔ ۲٫۳۳٫۰ و بالاتر.
- **Apache Kafka** نسخهٔ ۳٫۰ و بالاتر، در حالت KRaft و ZooKeeper. [راهنمای Kafka](/guide/kafka) و [نسخه‌های پشتیبانی‌شدهٔ بروکر](/guide/supported-versions) را ببینید (انگلیسی).
- **RabbitMQ**: به‌زودی؛ هنوز پشتیبانی نمی‌شود.

Rahdar از طریق [OpenBroker](https://github.com/sudoitir/openbroker)، یک SDK متن‌باز بروکر با یک درایور برای هر سیستم، با بروکرها صحبت می‌کند.

## با یک فرمان نصبش کنید

```bash
git clone https://github.com/sudoitir/rahdar && cd rahdar
just up          # دو replica از Rahdar + HAProxy + Postgres، با کلیدهای تولیدشده و پین‌شده به آخرین انتشار
```

بروکرهای شما از پیش وجود دارند؛ Rahdar آن‌ها را ثبت می‌کند، اجرا نمی‌کند. کلاستر را در رابط کاربری با URL مدیریتی Artemis یا bootstrap serverهای Kafka ثبت کنید؛ پیش از ذخیره، بررسی می‌شود که به تک‌تک بروکرها می‌رسد. [شروع سریع کامل ←](/fa/guide/quickstart)

::: warning مرحلهٔ Alpha
در حال توسعهٔ فعال و هنوز کامل نیست. ایمیج‌های منتشرشده buildهای پیش‌پایدار dev هستند (`sudoit1/rahdar:dev`؛ هنوز `:latest` وجود ندارد). منتظر تغییرات ناسازگار باشید.
:::

<small>Apache، Apache Kafka، Kafka، Apache ActiveMQ، ActiveMQ و Artemis نشان‌های تجاری بنیاد نرم‌افزار Apache هستند؛ RabbitMQ نشان تجاری مالک آن است. Rahdar به آن‌ها وابسته نیست و از سوی آن‌ها تأیید نشده است.</small>
