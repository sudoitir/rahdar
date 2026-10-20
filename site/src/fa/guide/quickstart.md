---
title: شروع سریع
description: اجرای Rahdar و Postgres آن از ایمیج منتشرشده، ثبت اولین کلاستر، و ورود به سیستم.
---

# شروع سریع

Rahdar دو چیز لازم دارد: یک پایگاه‌دادهٔ PostgreSQL، و دسترسی شبکه به endpointهای مدیریتی بروکرهایتان. کلاسترهای Artemis شما از پیش وجود دارند و از طریق رابط کاربری ثبت می‌شوند — هیچ‌چیز در اینجا بروکری را بالا نمی‌آورد.

::: warning نسخهٔ آلفا
ایمیج‌های منتشرشده buildهای dev پیش از پایدار هستند (`sudoit1/rahdar:dev`؛ هنوز `:latest` منتشر نمی‌شود). بین انتشارها تغییرات ناسازگار را انتظار داشته باشید.
:::

## با `just`، از روی یک clone

[`just`](https://github.com/casey/just#packages) همان task runner ای است که این مخزن استفاده می‌کند. اجرای `just` به‌تنهایی همهٔ taskها را گروه‌بندی‌شده فهرست می‌کند.

```bash
git clone https://github.com/sudoitir/rahdar && cd rahdar
just up
```

`just up` ابتدا `just setup` را اجرا می‌کند: روی یک checkout تازه، فایل `deploy/compose/.env` را با یک `SECRET_KEY` و `DB_PASSWORD` تصادفی می‌نویسد و `RAHDAR_IMAGE` را به تازه‌ترین تگ منتشرشده پین می‌کند. سپس دو replica از Rahdar، HAProxy و Postgres را بالا می‌آورد و منتظر می‌ماند تا آماده شوند (`just up-single` فقط یک replica و بدون load balancer بالا می‌آورد). آدرس <http://localhost:8080> را باز کنید.

بعداً برای جلو بردن پین نسخه، دوباره `just setup` را اجرا کنید.

## بدون `just`

```bash
base=https://raw.githubusercontent.com/sudoitir/rahdar/main/deploy/compose
mkdir -p rahdar/compose rahdar/postgres && cd rahdar/compose   # the compose file mounts ../postgres
curl -sO "$base/compose.prod.yaml"
for f in postgresql.conf 10-pg-stat-statements.sql; do curl -s "${base%/compose}/postgres/$f" -o "../postgres/$f"; done
curl -s "$base/.env.example" -o .env   # سپس ویرایشش کنید — بخش پیکربندی را ببینید
docker compose -f compose.prod.yaml --env-file .env up -d
```

یا یک کانتینر تنها، روی Postgres ای که از پیش دارید:

```bash
docker run -p 8080:8080 \
  -e RAHDAR_DB_URL=jdbc:postgresql://db:5432/rahdar \
  -e RAHDAR_DB_USER=rahdar \
  -e RAHDAR_DB_PASSWORD=... \
  -e RAHDAR_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/rahdar:dev
```

## اولین ورود

نام کاربری `admin` است. اولین اجرا روی یک پایگاه‌دادهٔ خالی رمز عبور را می‌سازد و **فقط یک بار** در لاگ کانتینر چاپ می‌کند:

```bash
docker compose -f compose.prod.yaml logs rahdar | grep -A4 'Created administrator'
```

در اولین ورود مجبور می‌شوید رمز تازه‌ای تعیین کنید. اگر آن رمز اولیه را پیش از تغییرش گم کنید، تنها راه بازیابی، بازنشانی مستقیم همان سطر در Postgres است — هنوز جریانی برای بازیابی رمز وجود ندارد.

نقش داخلی **ADMIN** تأیید دومرحله‌ای می‌خواهد؛ پس بلافاصله بعد از تعیین رمز، Rahdar از شما می‌خواهد یک برنامهٔ احراز هویت (کد QR را اسکن کنید و با یک کد تأیید کنید) یا یک passkey راه بیندازید، و ده کد بازیابی یک‌بارمصرف را **فقط یک بار** نشان می‌دهد. آن‌ها را نگه دارید: اگر دستگاه گم شود، راه بازگشت شما هستند. از آن به بعد هر ورود، رمز و سپس یک کد می‌خواهد.

اسکریپت یا `curl` هم همین‌طور وارد می‌شود. برای حسابی که روش دومی دارد، `POST /api/v1/auth/login` پاسخ `{"status": "SECOND_FACTOR_REQUIRED", …}` می‌دهد و `POST /api/v1/auth/second-factor` مقدار `{"totpCode": "123456"}` را می‌گیرد. حسابی که هنوز چیزی ندارد با `POST /api/v1/auth/mfa/totp` شروع می‌کند (`secret` و `otpauthUri` را برمی‌گرداند) و با `POST /api/v1/auth/mfa/totp/confirm` و `{"code": "123456"}` تأیید می‌کند؛ پاسخ شامل کدهای بازیابی است. کلیدهای API کد نمی‌خواهند.

## ثبت یک کلاستر

وارد شوید و به **Clusters ← Add** بروید. شما فقط endpoint مدیریتی یک نود seed و اعتبارنامه‌اش را می‌دهید؛ باقی توپولوژی از خود بروکر کشف می‌شود. اعتبارنامه‌ها با `RAHDAR_SECRET_KEY` رمزنگاری‌شده ذخیره می‌شوند.

اگر قابلیتی در دسترس نباشد — کلاینت Core قابل دسترسی نباشد، یا عملیات مدیریتی‌ای در معرض نباشد — Rahdar می‌گوید کدام قابلیت است و دقیقاً همان قطعهٔ `broker.xml` را که آن را فعال می‌کند نشان می‌دهد، به‌جای پنهان‌کردن آن قابلیت.

## پشت یک reverse proxy

جریان زندهٔ رابط کاربری، Server-Sent Events روی `GET /api/v1/stream` است و پروکسی جلوی Rahdar **نباید آن را بافر کند**:

- nginx — روی همان مسیر `proxy_buffering off;`
- Apache — بافر خروجی روی آن مسیر خاموش
- Traefik — بدون تغییر کار می‌کند

بدون این، گراف توپولوژی و جدول صف‌ها فقط با poll پنج‌ثانیه‌ای به‌روز می‌شوند، که شبیه محصولی به‌نظر می‌رسد که به‌زحمت زنده است.

## با بروکرهای یک‌بارمصرف امتحانش کنید

اگر می‌خواهید پیش از وصل‌کردن به هر چیز واقعی آن را در عمل ببینید، مخزن یک dev stack دارد که Rahdar را از سورس می‌سازد، دو جفت واقعی live/backup آرتمیس بالا می‌آورد و آن‌ها را با ترافیکی پر می‌کند که شبیه یک estate واقعی است — از جمله یک آدرس بدون مصرف‌کننده که عمقش بالا می‌رود، یک انباشت واقعی dead-letter، و یک نود متوقف‌شده.

```bash
just dev-up                          # Postgres، یک جفت آرتمیس، و Rahdar
ADMIN_PASSWORD=… just demo           # جفت دوم، به‌همراه ترافیک واقع‌نما
```

رمزی که `just dev-up` چاپ می‌کند یک‌بارمصرف است. در اولین اجرا، seed به `NEW_ADMIN_PASSWORD=…` (رمزی که خودتان انتخاب می‌کنید) نیاز دارد؛ رمز را عوض می‌کند، برای `admin` برنامهٔ احراز هویتی را که نقشش می‌خواهد راه می‌اندازد و `ADMIN_TOTP_SECRET` را چاپ می‌کند. اجراهای بعدی، از جمله `just shots` و `just demo-gif`، به `ADMIN_PASSWORD` برابر رمز جدید و `ADMIN_TOTP_SECRET` برابر همان کلید نیاز دارند.

هیچ‌چیز در آن seed مستقیماً در `metric_sample` یا `subscription_snapshot` نمی‌نویسد — ترافیک با CLI خود بروکر تولید و مصرف می‌شود، پس نمودارها اندازه‌گیری‌اند، نه داستان.

## ادامه

- [پیکربندی](/fa/guide/configuration) — هر متغیری که می‌خواند
- [پیکربندی بروکر](/fa/guide/broker-configuration) — اعلام اینکه یک کلاستر چه باید اجرا کند، و بستن شکاف‌های قابلیتی که نخستین اجرا گزارش می‌کند
- [کنسول SQL](/fa/guide/sql-console) — پرسیدن پرسشی که از مرز صف‌ها عبور می‌کند
- [سرور MCP](/fa/guide/mcp) — همان قابلیت‌ها برای یک دستیار
