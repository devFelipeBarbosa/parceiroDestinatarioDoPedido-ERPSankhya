package br.com.conceito.parcdest.event;

import br.com.conceito.parcdest.service.ResolvedorParceiroDestinatario;
import br.com.conceito.parcdest.util.Configuracao;
import br.com.conceito.parcdest.util.Log;

import br.com.sankhya.extensions.eventoprogramavel.EventoProgramavelJava;
import br.com.sankhya.jape.event.ModifingFields;
import br.com.sankhya.jape.event.PersistenceEvent;
import br.com.sankhya.jape.event.TransactionContext;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.vo.EntityVO;

import java.math.BigDecimal;

/**
 * Evento Programavel Java na entidade CabecalhoNota (TGFCAB).
 *
 * Duas responsabilidades, cada uma com sua chave no {@code parcdest.properties}:
 *
 * <ol>
 *   <li>{@code tracing=ON} — registra o ciclo de persistencia. Nao altera dado.</li>
 *   <li>{@code workaround=ON} — FASE 2: preenche o CODPARCDEST que o Portal de
 *       Importacao de XML deixa zerado, no unico momento em que isso e possivel.</li>
 * </ol>
 *
 * Por que so no {@code beforeInsert}: a trigger {@code TRG_DLT_TGFCAB_ESTTERC} e
 * {@code BEFORE UPDATE} e sua condicao nao olha o estado da nota (secao 7.1). Nao existe
 * janela de UPDATE em momento algum do ciclo — antes ou depois de confirmar. Uma nota que
 * nasce com o campo preenchido nao passa por validacao nenhuma.
 *
 * Registrar no Sankhya em: Configuracoes &gt; Eventos Programaveis, entidade CabecalhoNota,
 * classe {@code br.com.conceito.parcdest.event.ParceiroDestinatarioTracer}.
 */
public class ParceiroDestinatarioTracer implements EventoProgramavelJava {

    private static final String CODPARCDEST = "CODPARCDEST";
    private static final String CODTIPOPER = "CODTIPOPER";

    private static final String[] CAMPOS_BASE = {
        "NUNOTA", "NUMNOTA", "CODPARC", CODPARCDEST, CODTIPOPER, "DHTIPOPER"
    };

    /** Correlaciona o BEFORE COMMIT com os eventos ja registrados na mesma transacao. */
    private static final ThreadLocal<Integer> TRANSACAO_RELEVANTE = new ThreadLocal<Integer>();

    @Override
    public void beforeInsert(PersistenceEvent evento) {
        registrar("BEFORE_INSERT", evento);
        preencherDestinatario(evento);
    }

    @Override
    public void afterInsert(PersistenceEvent evento) {
        registrar("AFTER_INSERT", evento);
    }

    @Override
    public void beforeUpdate(PersistenceEvent evento) {
        registrar("BEFORE_UPDATE", evento);
    }

    @Override
    public void afterUpdate(PersistenceEvent evento) {
        registrar("AFTER_UPDATE", evento);
    }

    @Override
    public void beforeDelete(PersistenceEvent evento) {
        registrar("BEFORE_DELETE", evento);
    }

    @Override
    public void afterDelete(PersistenceEvent evento) {
        registrar("AFTER_DELETE", evento);
    }

    @Override
    public void beforeCommit(TransactionContext contexto) {
        try {
            Integer relevante = TRANSACAO_RELEVANTE.get();
            if (relevante == null) {
                return;
            }
            TRANSACAO_RELEVANTE.remove();
            if (relevante.intValue() == System.identityHashCode(contexto)) {
                Log.info("evento=BEFORE_COMMIT tx=" + relevante);
            }
        } catch (Exception falhaDeTracing) {
            protegerTransacao(falhaDeTracing);
        }
    }

    /**
     * FASE 2. Grava o CODPARCDEST no VO antes da persistencia, quando ha fonte
     * deterministica. Qualquer duvida e nao-atuacao (secao 16): a nota segue como hoje,
     * com zero, e o motivo vai para o log.
     */
    private void preencherDestinatario(PersistenceEvent evento) {
        try {
            Configuracao config = Configuracao.atual();
            if (!config.workaroundAtivo()) {
                return;
            }

            DynamicVO vo = comoDynamicVO(evento.getVo());
            if (vo == null) {
                return;
            }
            if (!config.topElegivel(numero(vo, CODTIPOPER))) {
                return;
            }

            BigDecimal atual = numero(vo, CODPARCDEST);
            if (atual != null && atual.signum() > 0) {
                return; // ja veio preenchido: nada a corrigir
            }

            BigDecimal destinatario = ResolvedorParceiroDestinatario.resolver(
                evento.getJdbcWrapper(),
                valorTexto(vo, "CHAVENFE"),
                numero(vo, "CODPARC"),
                numero(vo, "CODEMP"));

            if (destinatario == null) {
                return; // o resolvedor ja registrou o motivo
            }

            vo.setProperty(CODPARCDEST, destinatario);
            Log.info("workaround=APLICADO NUNOTA=" + valor(vo, "NUNOTA")
                   + " NUMNOTA=" + valor(vo, "NUMNOTA")
                   + " CODPARCDEST=" + destinatario);

        } catch (Exception falhaDoWorkaround) {
            // Secao 21: o workaround nunca derruba a importacao. Falhou, a nota entra
            // com zero — exatamente o comportamento de hoje, sem o componente.
            protegerTransacao(falhaDoWorkaround);
        }
    }

    private static String valorTexto(DynamicVO vo, String campo) {
        Object bruto = propriedade(vo, campo);
        return bruto == null ? null : bruto.toString();
    }

    /**
     * Um tracer nunca pode derrubar a transacao do usuario: toda falha e engolida e logada.
     * Esta e a unica excecao legitima a regra de nao capturar Exception generica.
     */
    private void registrar(String nomeDoEvento, PersistenceEvent evento) {
        try {
            Configuracao config = Configuracao.atual();
            if (!config.tracingAtivo()) {
                return;
            }

            DynamicVO vo = comoDynamicVO(evento.getVo());
            if (vo == null) {
                return;
            }

            ModifingFields alterados = camposAlterados(evento);
            boolean mexeuNoParcDest = alterados != null && alterados.isModifing(CODPARCDEST);

            // Secao 13: escopo restrito. TOP elegivel, ou qualquer alteracao de CODPARCDEST
            // (esta ultima e o UPDATE que dispara o ORA-20101, venha de onde vier).
            if (!config.topElegivel(numero(vo, CODTIPOPER)) && !mexeuNoParcDest) {
                return;
            }

            int tx = System.identityHashCode(evento.getTransactionContext());
            TRANSACAO_RELEVANTE.set(Integer.valueOf(tx));

            StringBuilder linha = new StringBuilder(256);
            linha.append("evento=").append(nomeDoEvento).append(" tx=").append(tx);
            for (String campo : CAMPOS_BASE) {
                linha.append(' ').append(campo).append('=').append(valor(vo, campo));
            }
            for (String campo : config.camposExtras()) {
                linha.append(' ').append(campo).append('=').append(valor(vo, campo));
            }
            if (mexeuNoParcDest) {
                linha.append(" OLD.").append(CODPARCDEST).append('=')
                     .append(texto(alterados.getOldValue(CODPARCDEST)));
                linha.append(" NEW.").append(CODPARCDEST).append('=')
                     .append(texto(alterados.getNewValue(CODPARCDEST)));
            }
            linha.append(" status=TRACED");

            Log.info(linha.toString());

        } catch (Exception falhaDeTracing) {
            protegerTransacao(falhaDeTracing);
        }
    }

    private void protegerTransacao(Exception falha) {
        try {
            Log.erro("Falha no tracing (evento ignorado, transacao preservada).", falha);
        } catch (Exception falhaAoLogar) {
            System.err.println(Log.PREFIXO + " falha dupla no tracing: " + falhaAoLogar.getMessage());
        }
    }

    private static DynamicVO comoDynamicVO(EntityVO vo) {
        return vo instanceof DynamicVO ? (DynamicVO) vo : null;
    }

    private static ModifingFields camposAlterados(PersistenceEvent evento) {
        try {
            return evento.getModifingFields();
        } catch (Exception naoDisponivelNesteEvento) {
            return null;
        }
    }

    private static BigDecimal numero(DynamicVO vo, String campo) {
        Object bruto = propriedade(vo, campo);
        if (bruto instanceof BigDecimal) {
            return (BigDecimal) bruto;
        }
        if (bruto == null) {
            return null;
        }
        try {
            return new BigDecimal(bruto.toString().trim());
        } catch (NumberFormatException naoENumero) {
            return null;
        }
    }

    /** Campo ausente e campo nulo sao coisas diferentes no diagnostico — nao unifique. */
    private static String valor(DynamicVO vo, String campo) {
        try {
            if (!vo.containsProperty(campo)) {
                return "<ausente>";
            }
        } catch (Exception campoIlegivel) {
            return "<erro>";
        }
        return texto(propriedade(vo, campo));
    }

    /** Nao usa as*() para nao converter: o tracer registra o valor cru, inclusive nulo. */
    private static Object propriedade(DynamicVO vo, String campo) {
        try {
            return vo.containsProperty(campo) ? vo.getProperty(campo) : null;
        } catch (Exception campoAusenteOuIlegivel) {
            return null;
        }
    }

    private static String texto(Object valor) {
        return valor == null ? "<null>" : valor.toString();
    }
}
