# Arquitetura do ClipForge

## Android

Aplicativo nativo em Java, com interface personalizada e pipeline local de vídeo.

Principais componentes:

- `MainActivity` — navegação, autenticação e fluxo principal.
- `ClipForgeView` — interface visual.
- `VideoAnalyzer` — análise local por amostragem para localizar mudanças e possíveis highlights.
- `VideoClipper` — geração de cortes com `MediaExtractor` e `MediaMuxer`.
- `SupabaseApi` — autenticação e comunicação segura com serviços Supabase.

## Supabase

O projeto separa as responsabilidades em três backends:

- **Core** — autenticação e serviços do aplicativo.
- **Raw Video** — armazenamento temporário de gameplays pesadas.
- **Generated Clips** — armazenamento temporário dos clips.

Credenciais administrativas nunca devem ser incluídas no APK. Operações privilegiadas devem ocorrer em Edge Functions ou outro backend confiável.

## Retenção

- A gameplay original do usuário no celular não deve ser removida pelo aplicativo sem ação explícita do usuário.
- Cópias temporárias na nuvem devem ser removidas após o processamento.
- Clips temporários na nuvem devem expirar em até 24 horas.
- O arquivo final exportado para o dispositivo permanece no celular.
