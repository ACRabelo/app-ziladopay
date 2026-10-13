# Totem — app Android

App em tela cheia que mostra a interface do totem num WebView, conversa com o
servidor (`totem-api`, o Laravel) e cobra no cartão pela Minizinha (PlugPag).
O PIX é gerado pelo servidor e aparece como QR Code na tela.

```
┌──────────────────── Celular / tablet Android ────────────────────┐
│  WebView: assets/totem/index.html (toda a interface)             │
│     │ TotemApi.chamar(...)            │ TotemNativo.pagar(...)   │
│     ▼                                 ▼                          │
│  ApiBridge.kt ──HTTP──► totem-api   TotemBridge.kt ─► PaymentGateway
│  (coloca o token)       (Laravel)                     ├─ SimulatedGateway   (sabor "simulado")
│  TotemServidor.kt                                     └─ PlugPagGateway ─BT─► Minizinha (sabor "plugpag")
│  (token cifrado)                                                 │
│     │ TotemImpressora.imprimir(...)                              │
│     ▼                                                            │
│  ImpressoraBridge.kt ─BT─► impressora térmica 58 mm (ESC/POS)    │
└──────────────────────────────────────────────────────────────────┘
```

## Primeira configuração de um aparelho (pareamento)

1. No painel: **Estrutura → Totens**, crie o totem na loja certa (ou toque em **Parear**
   num totem que já existe). Aparece um código de 8 números, que vale 10 minutos e uma vez só.
2. No aparelho: abra o app. Sem pareamento ele abre na tela **Parear este aparelho**.
   Confira o endereço do servidor, digite o código e toque em **Parear**.
3. Pronto. O app guarda o acesso e passa a abrir direto no cardápio da loja.

O aparelho não escolhe loja: quem define é o cadastro do totem no painel. Para mudar
de loja, edite o totem no painel. Para trocar o aparelho, toque em **Parear** de novo:
quando o código novo for usado, o aparelho antigo para de funcionar e, na próxima
chamada ao servidor, volta para a tela de pareamento.

Para desfazer pelo aparelho: 5 toques no canto superior direito → **Desparear**.

## Testar hoje, sem maquininha e sem conta de PIX

1. No `totem-api`: `php artisan serve --host=0.0.0.0` (o `--host` é o que deixa o celular
   chegar no Mac pela rede).
2. No painel, em **Estrutura → Pontos de venda**, ligue o PIX com o provedor **Simulado (só para testes)**.
   Ele não cobra ninguém: o pagamento "cai" sozinho em cerca de 10 segundos. Pedido com valor
   terminado em 13 centavos nunca é pago, para testar cancelamento.
3. No Android Studio, em **Build Variants**, escolha `simuladoDebug` e rode no celular.
4. Na tela de pareamento, troque o servidor para o IP do Mac na rede, ex.
   `http://192.168.0.10:8000` (Mac e celular no mesmo Wi-Fi), e digite o código.

Fotos de produto aparecem com o mesmo endereço que o aparelho usou para chegar no servidor.

### Só a tela, no navegador do PC

A página funciona fora do app, com as duas pontes simuladas em JavaScript:

```sh
cd app/src/main/assets/totem && python3 -m http.server 8200
# abra http://localhost:8200/index.html?servidor=http://localhost:8000
```

No navegador o acesso fica no `localStorage` (no app, fica cifrado no Android).
Serve para mexer em layout e fluxo sem recompilar.

## Arquivos que importam

| Arquivo | O que faz |
|---|---|
| `gradle.properties` | Servidor padrão (`totem.api`), leitor Bluetooth padrão, modo quiosque |
| `app/src/main/assets/totem/index.html` | Toda a interface: pareamento, cardápio, pagamento, resultado, diagnóstico |
| `app/src/main/assets/totem/qrcode.js` | Biblioteca que desenha o QR Code (qrcode-generator, MIT) |
| `.../ApiBridge.kt` | Ponte JS ↔ servidor: faz as chamadas HTTP e coloca o token |
| `.../TotemServidor.kt` | Guarda o endereço do servidor e o token (cifrado com o Android Keystore) |
| `.../TotemBridge.kt` | Ponte JS ↔ leitor de cartão; fila única para chamadas bloqueantes |
| `.../ImpressoraBridge.kt` | Ponte JS ↔ impressora Bluetooth: só conecta e envia os bytes que a página montou |
| `.../MainActivity.kt` | WebView em tela cheia, permissões, bloqueio de navegação externa |
| `.../PaymentGateway.kt` | Contrato de pagamento no leitor |
| `app/src/simulado/.../GatewayFactory.kt` | Simulador da Minizinha |
| `app/src/plugpag/.../GatewayFactory.kt` | **Único arquivo que usa a biblioteca PlugPag** |

## Como a tela fala com o servidor

A página nunca recebe o token. Ela pede, o app chama:

```js
// página -> app
TotemApi.estado()                              // texto JSON: { pareado, servidor, versao }
TotemApi.parear(id, codigo, servidor)
TotemApi.chamar(id, "POST", "/orders", corpo)  // caminho relativo a /api/v1/totem
TotemApi.desparear()

// app -> página (resposta de parear e de chamar)
window.Totem.receber({ tipo: "api", id, status, corpo, erro })
// status = código HTTP; 0 = sem resposta (sem rede ou servidor fora)
```

A impressora segue o mesmo desenho:

```js
TotemImpressora.estado()               // texto JSON: { mac, nome } ou {} sem impressora
TotemImpressora.listar()               // texto JSON: { permissao, bluetoothLigado, dispositivos: [...] }
TotemImpressora.definir(mac, nome)     // mac vazio = não imprimir
TotemImpressora.imprimir(id, base64)   // bytes ESC/POS em Base64
window.Totem.receber({ tipo: "impressao", id, ok, mensagem, enviados })
// enviados = bytes entregues à impressora; -1 = não dá para saber
```

O contrato da API está em `totem-api/docs/api-totem.md`.

### Fluxo de uma venda

- **PIX:** cria o pedido → `POST /orders/{uuid}/pix` → desenha o QR Code de `pix.qr_code` →
  consulta `GET /orders/{uuid}` a cada 3 s até `paid`. Cancelar chama `/cancel`; se o servidor
  responder que já estava pago, a tela mostra aprovado.
- **Cartão:** cria o pedido → `TotemNativo.pagar(...)` com o código do pedido como referência →
  o resultado (aprovado ou recusado) vai para `POST /orders/{uuid}/payments`.
  O resultado fica numa fila gravada no aparelho até o servidor confirmar, mesmo se a
  rede cair ou o app fechar: sem `transactionCode` e `transactionId` no servidor não há estorno.
- Token recusado (`401`) em qualquer chamada leva de volta ao pareamento.
- Totem, loja ou empresa desativados (`403`) e servidor fora mostram "Totem indisponível"
  e tentam de novo sozinhos.
- Vendas bloqueadas pelo super admin (`sales.blocked` em `/me` e `/catalog`, ou `423` ao criar
  pedido ou gerar Pix) mostram "Totem fora de serviço" com a mensagem do servidor. O carrinho
  é descartado e o totem confere de novo a cada 30 s; liberou, volta sozinho ao início.
  Parado na tela inicial, o totem confere o servidor a cada minuto.
- Área de diagnóstico (5 toques no canto de cima, à direita) pede o **PIN do ponto de venda**, definido no
  painel em Estrutura → Pontos de venda → Acesso ao totem. Quem confere é o servidor
  (`POST /admin/unlock`, com limite de tentativas); o app guarda só um hash PBKDF2 do último PIN
  aceito, para entrar sem internet. Aparelho ainda não pareado entra direto (não há loja nem PIN).
  O diagnóstico fecha sozinho depois de 2 minutos sem toque, e a tela do PIN depois de 30 s.
- Cliente que abandona o totem: depois de 90 s o pedido pendente é cancelado e a tela volta ao início.

## Modo quiosque (o cliente não sai do app)

O app vira **Device Owner** do aparelho e trava a tela nele: some a barra de status e
os botões de início e de apps recentes, sem pedir confirmação. O totem também vira a
tela inicial, então abre sozinho quando o aparelho liga, e a tela fica acesa enquanto
estiver na tomada.

**Configurar (uma vez por aparelho):**

1. O aparelho não pode ter conta nenhuma (Google, Samsung). Num aparelho só para o totem,
   o mais simples é restaurar de fábrica e pular o login na configuração inicial. Num
   aparelho em uso, remova as contas em Configurações › Contas.
2. Ative a depuração USB, instale o app e rode:

   ```sh
   # sabor plugpag
   adb shell dpm set-device-owner br.com.totem.prototipo/.KioskAdmin
   # sabor simulado
   adb shell dpm set-device-owner br.com.totem.prototipo.simulado/br.com.totem.prototipo.KioskAdmin
   ```

   Deve responder `Success: Device owner set to package ...`. Se reclamar de contas,
   volte ao passo 1.
3. Abra o app. A trava liga sozinha. Depois disso pode adicionar a conta de volta, se quiser.

**Operador, pelo Diagnóstico (5 toques + PIN do ponto de venda), em "Modo quiosque":**

- **Sair do totem**: fecha o app e solta a trava, para manutenção (parear a maquininha
  no Bluetooth, mexer no Wi-Fi). Abrindo o app de novo, a trava volta.
- **Liberar aparelho**: o app deixa de ser Device Owner e o aparelho volta a ser um
  Android comum. Para voltar a ser totem, rode o comando adb de novo.

Sem Device Owner, `totem.quiosque=true` no `gradle.properties` usa o "fixar app" do
Android: ele pede confirmação, e dá para sair segurando Voltar + Recentes. Serve para
testar, não para deixar na loja.

Com a trava ligada, o Android não abre outros apps. Para parear um leitor ou impressora
nova no Bluetooth, use "Sair do totem" antes.

## Cartão com a Minizinha de verdade

1. Nada para baixar: o Gradle busca `br.com.uol.pagseguro:plugpag:4.15.1` no repositório
   Maven do PagBank no GitHub (configurado em `settings.gradle.kts`).
2. Pareie a Minizinha nas configurações de Bluetooth do aparelho (aperte `0` na Minizinha
   para ficar visível).
3. Em **Build Variants**, escolha `plugpagDebug` e rode.
4. Com 5 toques no canto superior direito abre o **Diagnóstico**: escolha o leitor na lista,
   faça o "Login PagBank" (uma vez, com a conta do estabelecimento) e teste o Bluetooth.
5. No painel, a loja precisa estar com **Cartão na maquininha** ligado.

Erro `-1025` ao conectar = leitor desligado, não pareado ou nome errado.
As assinaturas usadas em `app/src/plugpag/.../GatewayFactory.kt` foram conferidas no
`plugpag-4.15.1.aar` e no demo oficial (`pagseguro/plugpag/4.x/demo`).

## Impressora de comprovante (Bluetooth, 58 mm)

Serve impressora térmica Bluetooth que fale **ESC/POS** por Bluetooth clássico (SPP),
que é o caso das "mini impressoras 58 mm" de PDV. Não serve impressora de etiqueta ou
de foto que só funciona pelo aplicativo do fabricante (as "cat printer", por exemplo).

1. Ligue a impressora e pareie nas configurações de Bluetooth do aparelho.
   A senha costuma ser `0000` ou `1234`.
2. No app: 5 toques no canto superior direito → **Diagnóstico** → **Impressora de comprovante**
   → escolha a impressora na lista → **Imprimir teste**.
3. Pronto: toda venda aprovada (PIX ou cartão) imprime o comprovante com loja, código do
   pedido, itens, total e forma de pagamento. A tela de resultado tem **Imprimir de novo**.
   Com o **Modo evento** ligado na loja, depois do comprovante sai uma ficha por item (veja abaixo).

Para parar de imprimir, escolha **Não imprimir** na mesma lista. A escolha fica guardada
no aparelho. Funciona nos dois sabores (`simulado` e `plugpag`).

Se a impressora estiver desligada ou fora de alcance, a venda segue normalmente e a tela
pede para o cliente guardar o código do pedido. Falta de papel o app não percebe.

O comprovante é montado na página (`Escpos` e `montarComprovante` em `index.html`), com
32 colunas e texto sem acento, porque cada impressora barata usa uma tabela de acentos
diferente. Para mudar o layout não precisa mexer em Kotlin. No Logcat, filtro `ImpressoraBridge`.

Se o papel sair com letras cortadas ou linhas quebradas no meio, a impressora tem outra
largura: ajuste `COLUNAS` em `Escpos` (58 mm = 32; 80 mm = 48).

### Modo evento: uma ficha por item

No painel, em **Estrutura → Pontos de venda → Modo evento**. Ligado, o totem imprime o comprovante
da compra e, logo abaixo, **uma ficha para cada unidade vendida**: 3 cervejas saem em
3 fichas, e o cliente entrega uma a cada retirada. O comprovante sai com o aviso
"NAO VALE COMO FICHA".

```
          (comprovante)
TOTAL                   R$ 36,00
Pagamento: Pix
--------------------------------
  Retire com as fichas abaixo.
      NAO VALE COMO FICHA
- - - - - - - - - - - - - - - -
        Empresa Exemplo
   C h o p p   P i l s e n        (letra dobrada)
        3 0 0 m l
          [ QR Code ]
          4CYYP-ZGFBN
         VALE 1 UNIDADE
Pedido XR2NJQ8T        Ficha 2/3
09/10/2026 14:45
```

**As fichas nascem no servidor.** Quando o pedido é pago, o servidor cria uma ficha por
unidade, cada uma com um código único, e manda em `tickets` no pedido. O totem só imprime:
o código vira QR Code (desenhado como imagem, `Escpos.qr`, porque o comando próprio de QR
falta em muita impressora barata) e sai também por extenso, para digitar.

O atendente lê o QR no celular, em `<servidor>/balcao`, e o servidor aceita cada ficha uma
vez. Ficha inventada não existe no sistema e cópia é recusada na segunda leitura.

- **Sem impressora escolhida o totem não vende** e mostra "Falta a impressora de fichas".
- **PIX:** as fichas chegam junto com a confirmação do pagamento.
- **Cartão:** o servidor cria as fichas quando recebe o resultado do leitor. A tela mostra
  "Gerando suas fichas..." por um instante.
- **Servidor fora do ar na hora do cartão:** a tela avisa, sai só o comprovante com
  "FICHAS NAO EMITIDAS" e o pagamento fica na fila. Quando ele chega ao servidor as fichas
  são criadas, e o atendente dá baixa pelo **código do pedido** no balcão.
- **Imprimir de novo** está liberado: são as mesmas fichas, com os mesmos códigos.
- Se a conexão com a impressora cair no meio, a tela diz quantas fichas faltaram e imprime
  **só as que faltaram** (o comprovante também não se repete se já saiu). O app sabe quantos
  bytes entregou à impressora (`enviados`, em `ImpressoraBridge.kt`) e a página sabe em que
  byte cada ficha termina.
- Depois de conectar na impressora o app nunca manda os mesmos dados duas vezes.

O totem lê a opção ao abrir e a cada 5 minutos parado na tela inicial; para valer na hora,
**Diagnóstico → Recarregar cardápio**. Com o modo ligado, o "Imprimir teste" traz também uma
ficha de exemplo marcada "SEM VALOR", com um QR Code que o balcão responde "Ficha não
encontrada": serve para conferir se o QR sai legível nessa impressora. O layout está em
`escreverFicha`, em `index.html`.

Se o QR sair borrado ou o celular demorar para ler, aumente o tamanho do quadradinho em
`Escpos.qr` (o padrão é 8 pontos = 1 mm; o QR fica com 2,5 cm de lado).

O que o app não tem como saber: **falta de papel** e impressora que desliga depois de
receber os dados. Nesses casos a tela diz que imprimiu; o cliente usa "Imprimir de novo" ou
mostra o código do pedido ao atendente.

### Depurar a página dentro do app

Com o celular no USB, abra `chrome://inspect` no Chrome do PC e clique em *inspect*
no WebView do totem. Console e rede funcionam como num site normal (as chamadas ao
servidor não aparecem na aba de rede: quem faz é o app; veja o Logcat, filtro `ApiBridge`).

## Ainda não tem

- Segunda via para a cozinha, corte automático e gaveta (hoje sai só o comprovante ou as fichas do cliente).
- Reimprimir no totem as fichas de um pedido antigo (o balcão resolve pelo código do pedido).
- Aviso de falta de papel: impressora Bluetooth barata não informa.
- Leitura do código de pareamento por câmera (hoje é digitado).
- Estorno de cartão pela tela do totem.
