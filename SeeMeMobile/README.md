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
- Servico de acessibilidade com popup flutuante em cima de outros apps.
- Tracking ocular global com MediaPipe Face Landmarker.
- Cursor visual por movimento do rosto.
- Piscada esquerda intencional para tocar onde o cursor esta.
- Botoes flutuantes: Olhos, Calibrar, Clique, Voltar, Home e Fechar.

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
app/src/main/assets/face_landmarker.task
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

## Ativar controle em todos os apps

1. Instale e abra o app.
2. Toque em `Acessibilidade`.
3. Conceda permissao de camera e notificacoes se o Android pedir.
4. Na tela de configuracoes do Android, entre em `Apps baixados` ou `Servicos instalados`.
5. Ative `SeeMe Controle por Olhos`.
6. Volte para qualquer app.
7. Toque no botao flutuante `S`.
8. Toque em `Olhos`.
9. Olhe para frente e toque em `Calibrar`.
10. Mova levemente o rosto para mover o cursor.
11. Pisque o olho esquerdo por um instante para clicar.

Observacoes importantes:

- O Android exige ativacao manual do servico de acessibilidade. O app nao consegue ligar isso sozinho.
- A camera frontal fica em uso enquanto `Olhos` estiver ativo.
- Se o cursor estiver puxando para um lado, toque em `Calibrar` olhando para o centro da tela.
- Em alguns celulares, o Android pode encerrar camera em background se economia de bateria estiver agressiva. Desative otimizacao de bateria para o SeeMe se isso acontecer.

## O que ficou fora nesta primeira versao

- Controle ocular do mouse do Windows.
- Automacao por `pyautogui`, abertura de `.exe`, clipboard e screenshot do PC.
- Login real com banco SQLite.
- Assistente de voz "Bruna".
- Reconhecimento de Libras por `.h5`.

Essas partes precisam de adaptacao Android especifica. O MVP aqui prioriza o controle por visao que faz sentido no celular.
