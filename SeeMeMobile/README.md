# SeeMe Mobile

Versao inicial em Android/Kotlin do projeto SeeMe, baseada no projeto Python/Flask original.

O que ja funciona nesta base:

- App Android nativo em Kotlin.
- Tela de camera com CameraX.
- Reconhecimento de mao com MediaPipe Hand Landmarker.
- Contagem de dedos levantados.
- Acoes por gesto: abrir site, compartilhar texto, voltar para a web interna.
- Area "Web" interna em WebView, simulando as telas principais do Flask: home, atalhos e ajustes.
- Cadastro local em memoria de atalhos simples para os gestos 1 a 5.

## Requisitos

- Android Studio instalado.
- Celular Android fisico via USB recomendado para PC com 8 GB de RAM.
- Nao precisa usar emulador.

## Modelo do MediaPipe

Antes de compilar, baixe o modelo oficial:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\download-models.ps1
```

Isso cria:

```text
app/src/main/assets/hand_landmarker.task
```

## Rodar

Abra esta pasta no Android Studio:

```text
SeeMeMobile
```

Depois rode no celular fisico. Se quiser usar terminal:

```powershell
.\gradlew.bat assembleDebug
```

Caso a pasta ainda nao tenha Gradle Wrapper, abra uma vez no Android Studio ou rode `gradle wrapper` em uma maquina com Gradle instalado.

## O que ficou fora nesta primeira versao

- Controle ocular do mouse do Windows.
- Automacao por `pyautogui`, abertura de `.exe`, clipboard e screenshot do PC.
- Login real com banco SQLite.
- Assistente de voz "Bruna".
- Reconhecimento de Libras por `.h5`.

Essas partes precisam de adaptacao Android especifica. O MVP aqui prioriza o controle por visao que faz sentido no celular.
