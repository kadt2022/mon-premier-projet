# Serveur SFTP Java

Serveur SFTP de demonstration base sur Apache MINA SSHD.

## Lancement local

Compilation :

```powershell
.\gradlew.bat clean jar
```

Execution :

```powershell
java -jar .\build\libs\sftp-server-1.0.0.jar
```

Le processus tourne en mode service. Pour l'arreter, utilisez `Ctrl+C`.

## Configuration

Les arguments de ligne de commande restent supportes, mais les variables d'environnement sont plus adaptees a Docker.

Variables disponibles :

- `AUTH_MODE`: `password`, `pubkey` ou `both`
- `SFTP_PORT`: port d'ecoute du serveur
- `WEB_PORT`: port de l'interface web de demonstration
- `SFTP_ROOT`: chemin du depot SFTP
- `PROTECT_LOCAL_STORAGE`: `true` ou `false`
- `ENABLE_WEB_UI`: `true` ou `false`
- `SFTP_USERS`: liste `utilisateur=motdepasse` separee par `;` ou `,`

Exemple PowerShell :

```powershell
$env:AUTH_MODE = "both"
$env:SFTP_PORT = "2222"
$env:WEB_PORT = "8080"
$env:SFTP_ROOT = ".\sftp-root"
$env:SFTP_USERS = "user=password;admin=admin123"
java -jar .\build\libs\sftp-server-1.0.0.jar
```

Interface web :

- URL : `http://localhost:8080`
- ecran de connexion, explorateur, administration et audit
- la connexion web reutilise les comptes de demonstration du serveur

## Authentification par cle

En mode `pubkey` ou `both`, si `authorized_keys` est absent pour l'utilisateur principal, le serveur genere automatiquement une paire de cles de demonstration.

Emplacement par defaut pour `user` :

- cle privee : `sftp-root\user\.ssh\id_rsa`
- cle publique autorisee : `sftp-root\user\.ssh\authorized_keys`

Exemple client OpenSSH :

```powershell
sftp -i .\sftp-root\user\.ssh\id_rsa -P 2222 user@localhost
```

Dans WinSCP :

1. Protocole : `SFTP`
2. Hote : `localhost`
3. Port : `2222`
4. Utilisateur : `user`
5. `Avance > SSH > Authentification > Fichier de cle privee` : pointez vers `id_rsa`

## Docker

Construction de l'image :

```powershell
docker build -t sftp-server-local .
```

Execution avec un volume persistant :

```powershell
New-Item -ItemType Directory -Force .\docker-data | Out-Null
docker run --rm `
  -p 2222:2222 `
  -p 8080:8080 `
  -e AUTH_MODE=both `
  -e SFTP_PORT=2222 `
  -e WEB_PORT=8080 `
  -e SFTP_ROOT=/data `
  -e ENABLE_WEB_UI=true `
  -e SFTP_USERS='user=password;admin=admin123' `
  -v "${PWD}\docker-data:/data" `
  sftp-server-local
```

Points importants :

- le processus principal ne demande plus d'interaction clavier
- `docker stop` declenche un arret propre via le hook JVM
- le volume `docker-data` conserve les fichiers de depot, `authorized_keys` et les cles generees
- `PROTECT_LOCAL_STORAGE` n'a un effet NTFS complet que sous Windows ; dans un conteneur Linux, cette option n'ajoute pas de droits NTFS
- l'interface web de demo est exposee sur `http://localhost:8080`

## Docker Compose

Un exemple minimal est fourni dans [`compose.yaml`](/d:/claude/sftp/compose.yaml).

```powershell
docker compose up --build
```
