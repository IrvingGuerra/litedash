# Contribuir a LiteDash

¡Gracias! *English is welcome too.*

## Lo más útil

- **Reportar tu pantalla**: marca, plataforma, Android, resolución y si
  funcionan Bluetooth LE, OBD y la caja CAN (plantilla "Pantalla nueva").
- **Sensores de llantas** y **cajas CAN**: modelo exacto y, si puedes, capturas
  de los datos.
- Errores con pasos para reproducirlos.

## Código

1. Rama corta desde `main`: `feat/<módulo>-<tema>` o `fix/<módulo>-<tema>`.
2. Commits [Conventional Commits](https://www.conventionalcommits.org/) con la
   descripción en español: `feat(tpms): asignar sensores tocando la llanta`.
3. Antes del PR: `./gradlew assemblePlayDebug lint`. Si tocaste algo visible,
   capturas en horizontal y vertical, y en 1024×600.
4. Si el usuario lo nota, agrégalo a `CHANGELOG.md` bajo "Sin publicar".

**Reglas que no se negocian:**

- Todo módulo nuevo viene **apagado**, y apagado no cuesta nada: no inicializa
  código, no registra receptores y no pide permisos.
- Nada de *polling*: los datos se leen solo mientras se muestran.
- Nada de descargar código fuera de Google Play (sí datos: imágenes, modelos 3D).
- Nada de marcas ajenas en nombres, íconos o capturas.

## Licencia y acuerdo de contribución

El código es GPL-3.0. Al abrir un PR aceptas que tu aporte se publique con esa
licencia **y** que el responsable del proyecto (Irving Guerra) pueda incluirlo
también en las versiones oficiales de LiteDash, que pueden tener módulos de
pago. Tu aporte sigue siendo libre en este repositorio.
