# ClipForge

Aplicativo Android para transformar gameplays longas em clips curtos.

## Estrutura

- `app/` — aplicativo Android
- `.github/workflows/` — build automático do APK
- `docs/` — documentação técnica

## Backend

O app usa três projetos Supabase separados por responsabilidade:

1. autenticação e serviços do aplicativo
2. armazenamento temporário de vídeos originais
3. armazenamento temporário de clips gerados

Arquivos grandes e clips temporários devem ser removidos da nuvem após o processamento conforme a política do projeto.

## Segurança

Nunca adicione `service_role`, senha de banco, keystore privada ou outros segredos ao APK ou ao GitHub.
