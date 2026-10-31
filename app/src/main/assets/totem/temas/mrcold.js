/* =====================================================================
   Tema "Mr Cold" (sorveteria): creme, azul-petróleo, laranja e verde-água,
   letra arredondada. Vale só com body.tema-mrcold (ver temas/mrcold.css).

   O tema não fala com o servidor nem mexe em pedido: só desenha. Usa as funções
   da página (el, $, categorias, carrinho, alterar, irParaPagamento, reiniciar...).
   Ganchos que a página chama, quando existem:
     aplicar()                 uma vez, ao entrar no tema: acrescenta o que é só dele
     frase                     frase da tela inicial quando o painel não define uma
     logoEmpresa(url)          logo enviado em "Meu negócio" (null = sem logo); a página
                               chama ao carregar os dados do totem e ao trocar de tema
     iconeEntrega(valor, i)    desenho dentro do botão "comer aqui" / "levar"
     desenharCardapio()        cardápio inteiro (abas, cartões, carrinho)
     formulario(f)             depois de montar o formulário de cadastro
   ===================================================================== */
(function () {
  'use strict';

  const svg = texto => { const t = document.createElement('template'); t.innerHTML = texto.trim(); return t.content.firstChild; };

  /* Logo desenhada com a fonte Fredoka. Provisória: trocar pelo arquivo oficial do cliente
     (temas/mrcold-logo.svg) quando chegar. */
  const logo = (cor, classe) => svg(`
    <svg class="${classe || 'mc-logo'}" viewBox="0 0 300 250" role="img" aria-label="Mr. Cold">
      <path d="M30 158 A120 120 0 1 1 266 168 c14 6 22 20 16 36 c-6 14 -22 16 -32 10" fill="none" stroke="${cor}" stroke-width="7" stroke-linecap="round"/>
      <circle cx="30" cy="168" r="5" fill="${cor}"/>
      <text x="74" y="130" font-family="Fredoka" font-weight="600" font-size="104" fill="${cor}" letter-spacing="-2">Mr</text>
      <circle cx="198" cy="120" r="9" fill="#F28A55"/>
      <text x="48" y="228" font-family="Fredoka" font-weight="600" font-size="106" fill="${cor}" letter-spacing="-3">Cold</text>
    </svg>`);

  /* Desenhos dos botões da tela inicial. */
  const RAIOS = `<g stroke="#F28A55" stroke-width="5" stroke-linecap="round" fill="none">
      <path d="M16 30 l10 8"/><path d="M10 52 l12 1"/><path d="M104 30 l-10 8"/><path d="M110 52 l-12 1"/></g>`;
  const COPO = svg(`
    <svg class="mc-icone" viewBox="0 0 120 120" aria-hidden="true">${RAIOS}
      <path d="M60 10 c-8 8 -22 10 -24 22 c-12 2 -16 14 -10 20 h68 c6 -6 2 -18 -10 -20 c-2 -12 -16 -10 -24 -22z" fill="#FFF8EE"/>
      <path d="M44 30 c8 4 24 4 32 0 M36 44 c14 5 34 5 48 0" stroke="#F28A55" stroke-width="5" fill="none" stroke-linecap="round"/>
      <path d="M28 58 h64 l-7 50 a6 6 0 0 1 -6 5 h-38 a6 6 0 0 1 -6 -5z" fill="#fff"/>
      <text x="60" y="88" text-anchor="middle" font-family="Fredoka" font-weight="600" font-size="15" fill="#1C5473">Mr.</text>
      <text x="60" y="103" text-anchor="middle" font-family="Fredoka" font-weight="600" font-size="15" fill="#1C5473">Cold</text>
    </svg>`);
  const SACOLA = svg(`
    <svg class="mc-icone" viewBox="0 0 120 120" aria-hidden="true">${RAIOS}
      <path d="M46 28 v-6 a14 14 0 0 1 28 0 v6" stroke="#fff" stroke-width="5" fill="none"/>
      <path d="M30 28 h60 l6 82 h-72z" fill="#FFF8EE"/>
      <path d="M90 28 l14 10 l-8 72 z" fill="#F28A55"/>
      <text x="60" y="72" text-anchor="middle" font-family="Fredoka" font-weight="600" font-size="17" fill="#1C5473">Mr.</text>
      <text x="60" y="90" text-anchor="middle" font-family="Fredoka" font-weight="600" font-size="17" fill="#1C5473">Cold</text>
    </svg>`);
  const CARRINHO = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2 3h3l2.4 11.2a2 2 0 0 0 2 1.6h8.2a2 2 0 0 0 2-1.5L21 7H6.2" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><circle cx="10" cy="20" r="1.6" fill="currentColor"/><circle cx="18" cy="20" r="1.6" fill="currentColor"/></svg>`;

  /* Ícone de cada categoria pelo nome cadastrado no painel. */
  const ICONES = [
    [/sorvet|gelat|picol|casquinh|cone/, '🍦'], [/a[cç]a[ií]/, '🍇'], [/milk|shake|frapp/, '🥤'],
    [/receit|sobremes|doce|bolo|torta/, '🧁'], [/bebid|refri|suco|[aá]gua|drink/, '🥤'], [/quent|caf[eé]|ch[aá]\b|chocolate quente/, '☕'],
    [/lanch|sandu|burg|salgad/, '🍔'], [/levar|pote|kg|quilo|embal/, '🛍️'], [/chopp|cervej/, '🍺'], [/combo|promo/, '⭐'],
  ];
  const icone = nome => { const n = String(nome || '').toLowerCase(); return (ICONES.find(([r]) => r.test(n)) || [null, '🍨'])[1]; };

  let aba = null;          // índice da categoria aberta
  let folhaAberta = false; // resumo do pedido aberto por cima do cardápio
  let montado = false;
  /* Lugares com logo (tela inicial, formulário e topo do cardápio): o desenho do tema ou,
     se enviado no painel, o logo do cliente. `cor` é a do desenho naquele fundo. */
  const lugares = [];
  const lugar = (cor, classe) => { const l = {cor, classe, no: logo(cor, classe)}; lugares.push(l); return l.no; };

  const qtdItens = () => [...carrinho.values()].reduce((s, q) => s + q, 0);

  function aplicar() {
    if (montado) return;
    montado = true;

    // Tela inicial: logo grande antes da frase. Troca pelo do cliente em logoEmpresa().
    $('inicio').insertBefore(lugar('#1C5473', 'mc-logo so-tema'), $('frase'));

    // Formulário: moldura com logo e "X" (a moldura é neutra no visual padrão: display:contents).
    const form = $('formulario');
    const moldura = el('div', {class: 'mc-moldura'});
    moldura.append(el('button', {class: 'mc-fechar so-tema', type: 'button', 'aria-label': 'Fechar', onclick: () => pularFormulario()}, '✕'),
      lugar('#1C5473', 'mc-logo so-tema'), ...form.childNodes);
    form.append(moldura);

    // Cardápio do tema: estrutura própria; a do visual padrão fica escondida pelo CSS.
    const card = $('cardapio');
    card.append(el('div', {class: 'mc-cardapio so-tema'},
      el('header', {class: 'mc-topo'},
        el('button', {class: 'mc-voltar', type: 'button', onclick: () => reiniciar()}, '‹ Início'),
        lugar('#FFFFFF', 'mc-logo'),
        el('h1', {}, 'Nosso Cardápio ', el('span', {class: 'mc-emoji'}, '🍨')),
        el('p', {}, 'Escolha seus favoritos e monte seu pedido!'),
        Object.assign(el('button', {class: 'mc-carrinho', type: 'button', 'aria-label': 'Ver pedido', onclick: () => abrirFolha(true)}), {innerHTML: CARRINHO + '<b id="mcQtd" hidden></b>'}),
        el('button', {class: 'mc-entrega', id: 'mcEntrega', type: 'button', hidden: '', onclick: () => trocarEntrega()})),
      el('nav', {class: 'mc-abas', id: 'mcAbas'}),
      el('div', {class: 'mc-grade', id: 'mcGrade'}),
      el('div', {class: 'mc-barra', id: 'mcBarra', hidden: ''},
        el('button', {class: 'mc-sec', type: 'button', onclick: () => reiniciar()}, 'Cancelar'),
        el('button', {class: 'mc-ver', type: 'button', onclick: () => abrirFolha(true)},
          el('span', {id: 'mcBarraQtd'}), el('strong', {id: 'mcBarraTotal'}))),
      el('div', {class: 'mc-folha', id: 'mcFolha', hidden: '', onclick: e => { if (e.target.id === 'mcFolha') abrirFolha(false); }},
        el('div', {class: 'mc-folha-caixa'},
          el('h2', {}, 'Seu pedido'),
          el('div', {class: 'mc-folha-itens', id: 'mcFolhaItens'}),
          el('div', {class: 'mc-folha-total'}, el('span', {}, 'Total'), el('strong', {id: 'mcFolhaTotal'})),
          el('button', {class: 'mc-btn', id: 'mcPagar', type: 'button', onclick: () => { abrirFolha(false); irParaPagamento(); }}, 'Ir para pagamento'),
          el('button', {class: 'mc-btn mc-sec', type: 'button', onclick: () => abrirFolha(false)}, 'Continuar escolhendo')))));
  }

  /* Logo enviado no painel (Meu negócio) na tela inicial, no formulário e no topo do
     cardápio. Sem logo, ou se a imagem não carregar, volta o desenho do tema. */
  function logoEmpresa(url) {
    if (!montado) return;
    for (const l of lugares) {
      if ((l.no.dataset.url || null) === (url || null)) continue; // o /me roda a cada minuto: nada mudou
      const novo = url
        ? Object.assign(el('img', {class: l.classe + ' mc-logo-cliente', src: url, alt: ''}), {
            onerror: () => { if (l.no === novo) troca(l, logo(l.cor, l.classe)); },
          })
        : logo(l.cor, l.classe);
      if (url) novo.dataset.url = url;
      troca(l, novo);
    }
  }
  function troca(l, novo) {
    l.no.replaceWith(novo);
    l.no = novo;
  }

  function abrirFolha(sim) {
    folhaAberta = !!sim && qtdItens() > 0;
    desenharCardapio();
  }

  function foto(p, cat) {
    const caixa = el('div', {class: 'mc-foto'});
    const reserva = () => { caixa.textContent = ''; caixa.append(el('span', {class: 'mc-foto-icone'}, icone(cat.name))); };
    if (p.image_url) {
      const img = el('img', {src: p.image_url, alt: '', loading: 'lazy'});
      img.onerror = reserva;
      caixa.append(img);
    } else reserva();
    return caixa;
  }

  function acao(p) {
    const q = carrinho.get(p.id) || 0;
    if (!q) return el('button', {class: 'mc-add', type: 'button', onclick: () => alterar(p.id, 1)}, el('b', {}, '+'), ' Adicionar');
    return el('div', {class: 'mc-qtd'},
      el('button', {type: 'button', 'aria-label': 'Menos ' + p.name, onclick: () => alterar(p.id, -1)}, '−'),
      el('span', {}, String(q)),
      Object.assign(el('button', {type: 'button', 'aria-label': 'Mais ' + p.name, onclick: () => alterar(p.id, 1)}, '+'), {disabled: !podeMais(p)}));
  }

  function desenharCardapio() {
    if (!montado) return;
    const cats = categorias.filter(c => c.products && c.products.length);
    if (aba == null || aba >= cats.length) aba = 0;

    // Abas
    const abas = $('mcAbas'); abas.textContent = '';
    cats.forEach((c, i) => abas.append(el('button', {class: 'mc-aba' + (i === aba ? ' sel' : ''), type: 'button',
      onclick: () => { aba = i; $('mcGrade').scrollTop = 0; desenharCardapio(); }},
      el('span', {class: 'mc-aba-icone'}, icone(c.name)), el('span', {}, c.name))));
    abas.hidden = cats.length < 2;

    // Cartões da categoria aberta
    const grade = $('mcGrade'); const rolagem = grade.scrollTop; grade.textContent = '';
    if (!cats.length) grade.append(el('div', {class: 'mc-vazio'}, 'Este totem ainda não tem produtos no cardápio.'));
    const cat = cats[aba];
    if (cat) for (const p of cat.products) {
      grade.append(el('article', {class: 'mc-item' + (carrinho.get(p.id) ? ' no-pedido' : '')},
        foto(p, cat),
        el('div', {class: 'mc-corpo'},
          el('h3', {}, p.name),
          p.description ? el('p', {}, p.description) : null,
          seloRestam(p, 'mc-restam'),
          el('div', {class: 'mc-rodape'}, el('span', {class: 'mc-preco'}, brl(p.price_cents)), acao(p)))));
    }
    grade.scrollTop = rolagem;

    // Carrinho: contador, barra de baixo e resumo
    const n = qtdItens(), soma = total();
    $('mcQtd').hidden = !n; $('mcQtd').textContent = String(n);
    $('mcBarra').hidden = !n;
    $('mcBarraQtd').textContent = n === 1 ? 'Ver pedido · 1 item' : `Ver pedido · ${n} itens`;
    $('mcBarraTotal').textContent = brl(soma);
    const ent = $('mcEntrega');
    ent.hidden = !entrega; if (entrega) ent.textContent = rotuloEntrega(entrega) + ' · trocar';

    if (!n) folhaAberta = false;
    $('mcFolha').hidden = !folhaAberta;
    const itens = $('mcFolhaItens'); itens.textContent = '';
    for (const p of produtos()) {
      const q = carrinho.get(p.id); if (!q) continue;
      itens.append(el('div', {class: 'mc-linha'},
        el('div', {}, el('strong', {}, p.name), el('small', {}, brl(p.price_cents) + ' cada')),
        acao(p),
        el('span', {class: 'mc-preco'}, brl(p.price_cents * q))));
    }
    $('mcFolhaTotal').textContent = brl(soma);

    // A página padrão também usa estes dois (ex.: btnPagar desabilitado com carrinho vazio).
    $('total').textContent = brl(soma);
    $('btnPagar').disabled = soma <= 0;
  }

  function formulario(f) {
    // No tema, "Pular" vira o X do canto. Formulário obrigatório: o X cancela (volta ao início).
    const x = document.querySelector('#formulario .mc-fechar');
    if (x) x.setAttribute('aria-label', f.required ? 'Cancelar' : 'Pular');
  }

  window.TEMAS = window.TEMAS || {};
  window.TEMAS.mrcold = {
    nome: 'Mr Cold',
    descricao: 'sorveteria: creme, azul e laranja, cardápio em cartões com foto',
    frase: 'Bem-vindo à Mr Cold! Faça seu pedido com autonomia',
    aplicar, desenharCardapio, formulario, logoEmpresa,
    iconeEntrega: (valor, i) => (valor === 'takeaway' || (valor !== 'dine_in' && i > 0) ? SACOLA : COPO).cloneNode(true),
  };
})();
