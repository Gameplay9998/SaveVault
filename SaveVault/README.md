# SaveVault — backup de saves para Android

App em Kotlin + Jetpack Compose para fazer backup **manual e automático** de saves de jogos.

## O que ele faz

- **Duas abas:** *Android* (jogos nativos) e *Emuladores*.
- **Capa de cada jogo:** você escolhe uma foto da galeria do celular (o app reduz e guarda uma cópia).
- **Backup manual:** botão "Fazer backup agora" em cada jogo, ou o botão ☁ na barra para fazer backup de todos da aba.
- **Backup automático:** roda a cada 1, 3, 6, 12 ou 24 h (WorkManager). Com "só se o save mudou" ligado, ele compara os arquivos e só copia quando algo mudou.
- **Destino por jogo:** Local, Google Drive ou os dois.
- **Versões:** guarda as últimas N versões (1 a 20) e apaga as mais antigas. Dá para **restaurar** ou apagar qualquer versão.
- **Duas formas de ler o save:**
  - **Pasta (sem root):** você escolhe a pasta pelo seletor do Android. Serve para emuladores (RetroArch, PPSSPP, DraStic…) e jogos que salvam no armazenamento comum.
  - **Root:** para jogos Android que salvam em `/data/data/<pacote>` ou `Android/data/<pacote>`. Tem um seletor de apps instalados. Precisa de Magisk/KernelSU. Na restauração, o app fecha o jogo e acerta dono/permissões dos arquivos.

Cada backup é um `.zip` chamado `<Jogo>_<data>.zip`.
Local: dentro da pasta escolhida em Configurações, uma subpasta por jogo.
Drive: pasta **SaveVault** na raiz do seu Drive.

> ⚠️ No Android 11 ou mais novo, o sistema **não deixa** apps escolherem `Android/data` pelo seletor de pastas. Para esses saves, use o modo Root ou configure o emulador para gravar numa pasta comum (a maioria deixa).

---

## Como compilar

### Opção A: Android Studio
1. Abra a pasta `SaveVault` no Android Studio (Ladybug ou mais novo).
2. Espere o Gradle sincronizar.
3. **Run ▶** com o celular conectado, ou *Build → Build APK(s)*.

### Opção B: GitHub (sem instalar nada)
1. Crie um repositório e envie o projeto.
2. O workflow em `.github/workflows/build.yml` compila sozinho.
3. Baixe o APK em **Actions → (última execução) → Artifacts → SaveVault-debug**.

---

## Configurar o Google Drive (obrigatório para a nuvem)

O login do Google só funciona depois de registrar o app no Google Cloud (é grátis):

1. Acesse <https://console.cloud.google.com/> e crie um projeto.
2. **APIs e serviços → Biblioteca →** ative a **Google Drive API**.
3. **Tela de permissão OAuth:** tipo *Externo*, preencha nome e e-mail. Em *Usuários de teste*, adicione o seu Gmail.
4. **Credenciais → Criar credenciais → ID do cliente OAuth → Android**
   - Nome do pacote: `com.savevault.app`
   - SHA-1: o certificado que assina o seu APK. Para pegar:
     - Android Studio: aba *Gradle* → `app > Tasks > android > signingReport`
     - ou terminal: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`
     - Se compilar pelo GitHub Actions, cada execução usa uma chave de debug diferente. Crie uma keystore própria e configure-a no `signingConfigs` para manter o SHA-1 fixo.
5. Pronto. No app: **Configurações → Conectar conta Google**.

O app usa o escopo `drive.file`: ele **só enxerga os arquivos que ele mesmo criou** no seu Drive, nada mais.

---

## Estrutura do código

```
app/src/main/java/com/savevault/app/
├── data/        Modelos (Game, BackupVersion), repositório JSON e preferências
├── backup/
│   ├── SafTree.kt          acesso rápido a pastas escolhidas (Storage Access Framework)
│   ├── Archiver.kt         zip/unzip + "impressão digital" para detectar mudanças
│   ├── RootShell.kt        comandos su
│   ├── LocalStore.kt       onde os backups locais ficam
│   ├── DriveClient.kt      API REST do Google Drive v3 (upload resumable, lista, download)
│   ├── BackupManager.kt    backup, restauração, versões
│   └── AutoBackupWorker.kt agendamento + notificação
└── ui/          Telas Compose (Início com abas, Detalhes, Editar, Configurações)
```

## Limitações conhecidas
- O Android decide o horário exato do backup automático (pode atrasar para poupar bateria).
- Jogos que guardam o progresso só no servidor (online) não têm save local para copiar.
- Quando o Drive pede login de novo, o backup automático avisa por notificação. É só reconectar nas Configurações.
