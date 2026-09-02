# GamePerf — Portfolio

[🇪🇸 Español](#español) · [🇬🇧 English](#english)

---

## Español

Llevo dos años trabajando como QA en videojuegos móviles. La mayor parte del tiempo lo paso validando builds antes de que salgan a producción: pruebas funcionales, rendimiento, integraciones de SDKs de analítica y publicidad, localización, y bastante trabajo con ADB.

Este repositorio es una herramienta que construí porque necesitaba algo que no existía, o que existía pero a un precio que no tenía sentido para el uso que le daba.

### Por qué existe GamePerf

Las opciones habituales para medir rendimiento en juegos móviles son GameBench y PerfDog. Las dos están bien, pero las dos tienen un problema: cuestan dinero, requieren cuenta, y en muchos casos requieren tocar el juego para sacar los datos que de verdad importan.

En el trabajo del día a día, lo que necesito es conectar un teléfono, grabar una sesión de juego y tener un informe que le pueda pasar a desarrollo con algo concreto: "a partir del minuto tres la temperatura sube a 48 grados y ahí es donde se ve el bajón de FPS". Sin script, sin terminal, sin que el tester de turno tenga que saber qué es sysfs.

Así que lo construí. Primero para mí, luego lo fui abriendo porque tampoco tenía razón para no hacerlo.

### Qué demuestra sobre cómo trabajo

Construir esta herramienta me obligó a entender cosas que en teoría no son "mi trabajo" como QA: cómo lee Android el uso de GPU por sysfs, por qué el FPower es una métrica más honesta que el consumo de batería en bruto, cómo funcionan los wake locks y por qué Google te penaliza en la tienda si los gestionas mal.

No lo digo para impresionar. Lo digo porque creo que un QA que entiende por qué falla algo, y no solo que ha fallado, es mucho más útil para un equipo de desarrollo.

En el día a día eso se traduce en reportes que van directo al grano: adjunto el fragmento de log relevante, indico en qué dispositivo ocurre y en cuál no, y si puedo reproducirlo de forma consistente digo exactamente los pasos. Jira está lleno de bugs con "a veces falla" y sin más contexto. Intento que los míos no sean así.

### Stack y herramientas

En el trabajo uso Jira a diario, ADB para prácticamente todo lo relacionado con Android, y tengo bastante soltura verificando integraciones de SDKs de analítica como Singular, AppsFlyer o Firebase. También he trabajado con matrices de dispositivos en distintas versiones de Android, incluyendo Android 16 (SDK 36).

GamePerf está escrito en Kotlin con Compose Desktop. El sidecar de iOS está en Python con pymobiledevice3. Tiene más de 300 tests y dos pipelines de GitHub Actions: uno de CI y uno de release que construye los instaladores para Mac, Windows y Linux en paralelo.

### Contacto

- **LinkedIn**: [Sergio Pérez Casado](https://www.linkedin.com/in/sergio-p%C3%A9rez-casado-5290b2201/)
- **Email**: sergioperezc109@gmail.com

---

## English

I've been working as a QA engineer in mobile games for two years. Most of my time goes into validating builds before they ship: functional testing, performance, SDK integrations for analytics and advertising, localisation, and a fair amount of ADB work.

This repository is a tool I built because I needed something that didn't exist, or that existed but at a price that made no sense for what I was actually doing with it.

### Why GamePerf exists

The usual options for measuring performance on mobile games are GameBench and PerfDog. Both are solid, but both share a problem: they cost money, require an account, and in many cases require modifying the game to get the data that actually matters.

In day-to-day work, what I need is to plug in a phone, record a gameplay session, and get a report I can hand off to development with something concrete: "from minute three, temperature climbs to 48°C and that's exactly where the FPS drops". No script, no terminal, no expectation that the tester knows what sysfs is.

So I built it. First for myself, then I opened it up because there was no reason not to.

### What it says about how I work

Building this forced me to understand things that are technically outside QA's remit: how Android exposes GPU usage through sysfs, why FPower is a more honest metric than raw battery consumption, how wake locks work and why Google penalises you in the store if you mishandle them.

I'm not saying this to show off. I'm saying it because a QA engineer who understands *why* something breaks — not just *that* it broke — is a lot more useful to a development team.

In practice that means bug reports that get to the point: the relevant log snippet is attached, I note which devices reproduce it and which don't, and if I can reproduce it consistently I give exact steps. Jira is full of bugs that say "sometimes fails" with nothing else. I try to make sure mine aren't like that.

### Stack and tools

Day to day I use Jira, ADB for most things Android-related, and I'm comfortable verifying SDK integrations — Singular, AppsFlyer, Firebase, that kind of thing. I've also worked with device matrices across multiple Android versions, including Android 16 (API 36).

GamePerf is written in Kotlin with Compose Desktop. The iOS sidecar is Python with pymobiledevice3. It has 300+ tests and two GitHub Actions pipelines: one for CI and one for release, which builds the installers for Mac, Windows and Linux in parallel.

### Contact

- **LinkedIn**: [Sergio Pérez Casado](https://www.linkedin.com/in/sergio-p%C3%A9rez-casado-5290b2201/)
- **Email**: sergioperezc109@gmail.com
