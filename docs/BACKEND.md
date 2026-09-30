# Configuração do backend

## Projeto principal

Responsável por autenticação, Edge Functions e coordenação do processamento.

Edge Functions atualmente utilizadas:

- `clipforge-signup`
- `clipforge-login`
- `clipforge-storage-ticket` (prevista/configuração de storage)
- `clipforge-finalize` (prevista/configuração de limpeza)

## Google Login

O botão Google só deve ser habilitado quando o provider Google estiver configurado no Supabase Authentication com Client ID e Client Secret válidos.

## Segredos

Não versionar:

- `service_role`
- chaves secretas
- senhas de banco
- keystores de assinatura
- credenciais OAuth privadas

As chaves publishable podem ser usadas pelo cliente Android; autorização real deve continuar protegida por RLS e funções de servidor.
