import type { ManualChapter } from "./manualTypes";

export const MANUAL_CHAPTERS_ES: ManualChapter[] = [
  {
    id: "households",
    title: "Hogares, cuentas y apariencia",
    summary: "Cómo funcionan los hogares, roles, invitaciones, idioma, tema y ajustes personales.",
    route: { to: "/settings", labelKey: "nav.settings" },
    sections: [
      {
        id: "households-model",
        heading: "Hogares y roles",
        paragraphs: [
          "Todos los datos financieros —transacciones, presupuestos, patrimonio, ajustes FIRE y bancos— pertenecen a un hogar, no directamente a una persona. Puedes pertenecer a uno o varios hogares y crear nuevos desde Ajustes → Hogares.",
        ],
        bullets: [
          {
            term: "Propietario vs. Miembro",
            text: "Los propietarios gestionan los ajustes del hogar, moneda, idioma por defecto, categorías personalizadas, invitaciones, roles, notificaciones de Telegram, funciones de IA, instantáneas automáticas y el borrado total de datos. Los miembros pueden consultar y operar con todos los datos financieros, vincular sus propios bancos y gestionar las reglas de categorización.",
          },
          {
            term: "Cambiar de hogar y hogar por defecto",
            text: "Si perteneces a más de un hogar, aparece un selector dentro del menú del avatar. Puedes marcar un hogar como predeterminado para que cada nuevo inicio de sesión lo abra directamente.",
          },
          {
            term: "Al menos un propietario",
            text: "Un hogar debe mantener siempre al menos un propietario. Para ceder la propiedad, asciende antes a otro miembro a propietario. No se puede eliminar un hogar mientras alguien lo tenga marcado como predeterminado.",
          },
          {
            term: "Ajustes del hogar e invitaciones",
            text: "Los propietarios configuran el nombre, la moneda y el idioma por defecto del hogar, e invitan a nuevos miembros con enlaces de un solo uso válidos 14 días (enviados por correo si hay SMTP configurado, o copiables manualmente; las invitaciones activas se pueden reenviar o revocar).",
          },
        ],
      },
      {
        id: "account-language-theme",
        heading: "Cuenta, idioma y tema",
        paragraphs: [
          "Tus preferencias personales están en el menú del avatar y en Ajustes.",
        ],
        bullets: [
          {
            term: "Idioma (EN / ES)",
            text: "Cambiar entre inglés y español en el menú del avatar actualiza la interfaz al momento y guarda la preferencia en tu cuenta, de modo que los correos automáticos (restablecer contraseña, cambio de email) se envían en ese idioma.",
          },
          {
            term: "Tema (Claro / Oscuro / OLED / Sistema)",
            text: "Se guarda por navegador desde el menú del avatar. Sistema sigue el modo claro u oscuro del dispositivo; OLED usa negro puro para ahorrar batería en pantallas OLED de móvil.",
          },
          {
            term: "Contraseña, recuperación y cambio de correo",
            text: "Cambiar la contraseña o el correo pide tu contraseña actual. Si el servidor tiene correo saliente (SMTP) configurado, aparece «¿Olvidaste tu contraseña?» en la pantalla de acceso (enlace de un uso válido 60 minutos) y cambiar el email envía un enlace de confirmación de 60 minutos a la nueva dirección avisando a la anterior; sin SMTP, el cambio de correo se aplica al instante. En ambos casos se cierran todas tus sesiones activas.",
          },
        ],
      },
    ],
  },
  {
    id: "dashboard",
    title: "Panel de inicio",
    summary: "Resumen del mes y del año, gráfico de gastos y tarjetas configurables.",
    route: { to: "/dashboard", labelKey: "nav.dashboard" },
    sections: [
      {
        id: "home-overview",
        heading: "Tarjetas resumen y gráficos",
        paragraphs: [
          "Inicio muestra los totales de ingresos, gastos y neto del mes y del año en curso junto al gráfico mensual de gastos. Encima aparecen hasta siete tarjetas de un vistazo:",
        ],
        bullets: [
          { term: "Tasa de ahorro", text: "Tasa de los últimos 12 meses con un minigráfico de 24 meses, además del año en curso y el mes actual." },
          { term: "Coste fijo de vida", text: "Media de los últimos 12 meses en gastos de plantillas recurrentes (por día y por año), comparada con el gasto total incluyendo discrecionales." },
          { term: "Patrimonio actual", text: "Total de tu última instantánea de patrimonio (aparece en cuanto registras al menos una)." },
          { term: "Cartera", text: "Valor de mercado actual, capital invertido, invertido neto («dinero propio») y rentabilidad de tus posiciones." },
          { term: "Dinero prestado", text: "Saldo pendiente total de los préstamos activos a terceros y los tres mayores." },
          { term: "Efectivo", text: "Efectivo total entre las cuentas bancarias vinculadas que cuentan como efectivo y otro efectivo, desglosado por banco." },
          { term: "Movimientos bancarios pendientes", text: "Número de movimientos bancarios por revisar en la bandeja de entrada." },
        ],
        tip: "Las tarjetas dependientes de datos solo se muestran cuando tienen contenido. Cada persona puede ocultar cualquiera de las siete tarjetas en Ajustes → Paneles de inicio.",
      },
    ],
  },
  {
    id: "transactions",
    title: "Transacciones, categorías y devoluciones",
    summary: "Registro diario de ingresos y gastos, categorías personalizadas, filtros por URL y devoluciones.",
    route: { to: "/transactions", labelKey: "nav.transactions" },
    sections: [
      {
        id: "tx-recording",
        heading: "Registrar y filtrar transacciones",
        paragraphs: [
          "Las transacciones recogen ingresos y gastos operativos (las aportaciones de capital o inversiones van en Patrimonio → Flujos). El formulario de alta rápida destaca como chips las categorías que más usaste en los últimos 30 días.",
        ],
        bullets: [
          {
            term: "Filtros, ordenación y línea de totales",
            text: "Filtra por rango de fechas, rango de importe (desde/hasta), tipo (ingreso/gasto), categoría, grupo o Solo devoluciones, y pulsa cualquier cabecera de columna (Fecha, Categoría, Descripción, Importe) para ordenar. Los filtros y el orden se guardan en la URL para poder recargar o compartir la vista. Encima de la tabla, la línea de totales suma todo el conjunto filtrado, no solo la página visible.",
          },
          {
            term: "Borrado lógico",
            text: "Al borrar una transacción desaparece de listados y cálculos, pero conserva su rastro de auditoría.",
          },
        ],
      },
      {
        id: "categories",
        heading: "Categorías globales y personalizadas",
        paragraphs: [
          "Todos los hogares comparten un catálogo base de ingresos y ocho grupos de gasto (Hogar, Transporte, Supermercado, Compras, Ocio, Financiero, Salud, Personal). Los propietarios pueden crear categorías propias en Ajustes → Categorías personalizadas.",
        ],
        bullets: [
          {
            term: "Indicador de esencial",
            text: "Las categorías personalizadas de gasto pueden marcarse como esenciales para sumar en Coste de vida y en Lean FIRE (Solo esenciales).",
          },
          {
            term: "Renombrar vs. eliminar",
            text: "Renombrar una categoría personalizada o moverla de grupo mantiene intactos sus movimientos, presupuestos y plantillas. Eliminarla borra de forma permanente todas las transacciones, presupuestos y plantillas recurrentes asociadas.",
          },
        ],
      },
      {
        id: "refunds",
        heading: "Devoluciones",
        paragraphs: [
          "El dinero que vuelve de una compra anterior (una prenda devuelta, un billete reembolsado o un abono parcial) se registra como una devolución: un gasto con importe negativo fechado cuando entra el dinero y, opcionalmente, enlazado a la compra original.",
        ],
        bullets: [
          {
            term: "¿Por qué gasto negativo en lugar de ingreso?",
            text: "Anotarlo como ingreso infla tanto ingresos como gastos. Un gasto negativo descuenta el importe neto en su categoría y mes de devolución, manteniendo exactos los presupuestos, la tasa de ahorro y la base de gasto FIRE sin reescribir la compra original.",
          },
          {
            term: "Tres formas de registrarla",
            text: "1) En Transacciones → Pendientes, pulsa Marcar como devolución sobre un abono bancario. 2) En Transacciones, pulsa Registrar devolución sobre un gasto existente. 3) Edita cualquier transacción y activa Es devolución (útil para convertir reembolsos antiguos).",
          },
        ],
        tip: "Puedes registrar devoluciones parciales o varias para una misma compra; si superan el importe original, la aplicación muestra un aviso no bloqueante.",
      },
    ],
  },
  {
    id: "recurring-budgets",
    title: "Plantillas recurrentes y presupuestos",
    summary: "Movimientos periódicos automáticos y objetivos de gasto mensual o anual.",
    route: { to: "/recurring", labelKey: "nav.recurring" },
    sections: [
      {
        id: "recurring",
        heading: "Plantillas recurrentes",
        paragraphs: [
          "Usa Recurrentes para automatizar nómina, alquiler, hipoteca, suscripciones o seguros con periodicidad semanal, mensual o anual.",
        ],
        bullets: [
          {
            term: "Ajuste al final de mes",
            text: "Una plantilla mensual fijada el día 31 se genera automáticamente el último día en meses más cortos (por ejemplo, 28 o 29 de febrero).",
          },
          {
            term: "Ejecución diaria y manual",
            text: "Un proceso diario genera los vencimientos pendientes hasta hoy sin duplicados, y también puedes lanzar una plantilla al instante desde el listado. Editar o borrar una transacción generada no modifica su plantilla, y pausar una plantilla no toca el histórico.",
          },
        ],
      },
      {
        id: "budgets",
        heading: "Presupuestos",
        paragraphs: [
          "Define límites mensuales o anuales por categoría de gasto en Presupuestos. La vista mensual muestra lo gastado, el porcentaje consumido, el estado por color (en camino / cerca / superado) y una proyección a fin de mes según los días transcurridos. Los presupuestos anuales se consultan en la matriz anual.",
        ],
      },
    ],
  },
  {
    id: "wealth",
    title: "Patrimonio",
    summary: "Cartera de inversión, instantáneas, flujos de capital, estimación de efectivo, activos, pasivos y préstamos.",
    route: { to: "/networth", labelKey: "nav.wealth" },
    sections: [
      {
        id: "portfolio",
        heading: "Cartera (Posiciones, Distribución y Evolución)",
        paragraphs: [
          "Controla cripto, ETFs, acciones y fondos por lotes con contabilidad FIFO. Al registrar una compra, rellena dos cualesquiera de cantidad, precio unitario e importe total y el tercero se calcula automáticamente.",
        ],
        bullets: [
          {
            term: "Editar cualquier operación",
            text: "Puedes editar en línea cualquier campo de una operación, incluido cambiar entre Compra y Venta. Cada edición recalcula todo el historial FIFO de la posición para impedir ventas descubiertas y actualizar coste base, plusvalía realizada, XIRR y gráficos.",
          },
          {
            term: "Precios automáticos y Actualizar ahora",
            text: "Busca y vincula cada activo a un proveedor (CoinGecko/Binance en cripto; Yahoo Finance, EODHD o Twelve Data en acciones y ETFs; BCE vía Frankfurter en divisas; los fondos se valoran manualmente en instantáneas). Las operaciones en otra moneda congelan el tipo de cambio del BCE en la fecha de la operación. Un punto ámbar indica precios fuera de tolerancia (3 h en cripto, 4 días naturales en acciones/ETFs), los símbolos descatalogados muestran «El proveedor no la cotiza» sin perder su histórico y al desplegar la fila ves la fecha y hora exactas. Usa Actualizar ahora para refrescar las series abiertas bajo demanda (con un breve enfriamiento).",
          },
          {
            term: "Posiciones cerradas, ordenación y bases de rentabilidad",
            text: "Las posiciones con valor inferior a 1 € (cerradas o residuales) se ocultan tras el selector Mostrar cerradas sin alterar totales, pesos ni gráficos. Todas las columnas se ordenan al pulsar la cabecera. En Ajustes → Porcentajes de rentabilidad de cartera eliges si el % divide sobre Capital invertido abierto, Invertido neto («dinero propio»; cuando es ≤ 0 estás en «dinero de la casa») o Capital total rotado. La tarjeta XIRR muestra la rentabilidad anualizada ponderada por el dinero (o acumulada si el historial es menor de 1 año).",
          },
          {
            term: "TWR y Benchmarks",
            text: "Evolución muestra el valor diario junto a la curva TWR (rentabilidad ponderada por el tiempo, independiente de cuándo aportas o retiras). Opcionalmente superpón S&P 500, MSCI World, Oro y Bitcoin, todos convertidos a EUR para comparar sin sesgo de divisa.",
          },
          {
            term: "Alertas de precio por Telegram",
            text: "Si el hogar tiene Telegram activo, el icono de campana en cada posición abierta vinculada permite fijar un precio objetivo (en la moneda nativa del activo, hasta 50 alertas activas por hogar) con caducidad opcional. Se evalúan tras cada actualización de precios, avisan una sola vez y se cancelan automáticamente si cierras la posición.",
          },
        ],
      },
      {
        id: "snapshots",
        heading: "Instantáneas de patrimonio",
        paragraphs: [
          "Las instantáneas son la fuente de verdad de tu patrimonio neto (Activos − Pasivos) en una fecha concreta, con como máximo una instantánea por día. Las pestañas Distribución y Evolución muestran el reparto por clase y la trayectoria temporal junto a las aportaciones netas acumuladas (cuando registras Movimientos) para ver la revalorización de mercado.",
        ],
        bullets: [
          {
            term: "Autorrelleno y aviso por variación > 50%",
            text: "Al crear una instantánea se precargan las clases de mercado (Cripto, ETFs, Acciones, Fondos) desde Cartera, Efectivo desde su estimación por flujos, Activos con nombre desde sus series y Pasivos desde sus saldos o cuadros. Si algún valor varía más de un 50%, el formulario pide confirmación explícita. Una vez guardada, la instantánea queda congelada.",
          },
          {
            term: "Instantánea automática",
            text: "Los propietarios pueden activar la creación automática diaria, semanal o mensual en Ajustes → Instantánea automática.",
          },
        ],
      },
      {
        id: "flows-cash",
        heading: "Flujos: Movimientos y Efectivo",
        paragraphs: [
          "La pestaña Flujos agrupa dos subpestañas: Movimientos y Efectivo.",
        ],
        bullets: [
          {
            term: "Movimientos",
            text: "Registra aportaciones, retiradas de inversión y amortizaciones de deuda sin ensuciar ingresos ni gastos. Desbloquean la separación entre capital aportado y revalorización de mercado en Evolución y alimentan el XIRR real en FIRE.",
          },
          {
            term: "Efectivo (ajustes + estimación por flujos)",
            text: "Mantén una serie de ajustes al cierre del día (la verdad de tu efectivo; si hay varios ajustes el mismo día, manda el último creado) mientras la aplicación estima el saldo intermedio con los flujos posteriores a esa fecha: Transacciones (+ingreso / −gasto), Prestado (−prestado / +cobrado) y Movimientos (+retirada / −aportación / −pago de deuda). Cada tipo tiene su interruptor, y editar Efectivo al guardar una instantánea reancla la serie en esa fecha.",
          },
          {
            term: "Efectivo desde bancos vinculados",
            text: "Si tienes bancos vinculados, Efectivo lista el último saldo de cada cuenta, una casilla «cuenta como efectivo» y el campo Otro efectivo (cartera física u otras cuentas). Cada lectura diaria de saldos guarda automáticamente un ajuste Banco con el total para reanclar el efectivo cada día.",
          },
        ],
      },
      {
        id: "assets-liabilities-lent",
        heading: "Activos, Pasivos y Prestado",
        paragraphs: [
          "Gestiona elementos patrimoniales específicos y deudas en tres pestañas hermanas:",
        ],
        bullets: [
          {
            term: "Activos",
            text: "Inmuebles, vehículos u otros bienes con su propio histórico de valoraciones. Márcalos como inactivos (por ejemplo, tras venderlos) para conservar el histórico sin incluirlos en nuevas instantáneas.",
          },
          {
            term: "Pasivos simples y préstamos amortizables",
            text: "Lleva deudas simples por saldo histórico o configura préstamos amortizables por tramos (sistema francés, alemán, solo intereses o sin interés; introduce capital + tipo + plazo, fecha fin o cuota) con día de cargo, revisiones de tipo, reanclajes, simulación de amortizaciones anticipadas y un cuadro mensual autónomo que no duplica transacciones.",
          },
          {
            term: "Prestado (solo seguimiento)",
            text: "Anota dinero prestado a terceros sin interés, con interés simple o compuesto, imputación de cobros primero a intereses, calendarios recurrentes opcionales y estados Saldado o Incobrable (un préstamo incobrable se puede reabrir). Queda fuera del patrimonio neto y de las transacciones.",
          },
        ],
      },
    ],
  },
  {
    id: "analytics",
    title: "Analítica y Pregunta",
    summary: "Seis vistas de análisis del gasto, proyección de fin de mes y filtros en lenguaje natural.",
    route: { to: "/analytics", labelKey: "nav.analytics" },
    sections: [
      {
        id: "analytics-tabs",
        heading: "Las seis pestañas de Analítica",
        paragraphs: [
          "Analítica comparte un selector de rango (3m, 6m, YTD, 1a, 2a, todo o rango personalizado) que recuerda tu elección en cada pestaña durante la sesión.",
        ],
        bullets: [
          { term: "Diario", text: "Calendario mensual coloreado por gasto diario con detalle por día y patrones por día de la semana y día del mes." },
          { term: "Explorador", text: "Análisis en profundidad de una categoría o grupo con medias/medianas, comparativa con el año anterior, descripciones más frecuentes y un panel apilado multisección. Todo el estado vive en la URL." },
          { term: "Composición", text: "Gráfico de distribución de ingresos (incluido el ahorro), Coste de vida (medias mensuales esenciales vs. no esenciales) y reparto Recurrente vs. discrecional." },
          { term: "Cambios", text: "Mayores variaciones por categoría frente al año pasado o la media de 6 meses, más un mapa de calor categoría × mes (también con selector de ingresos) clicable para ir a sus transacciones." },
          { term: "Tendencias", text: "Comparativa interanual del mismo mes, evolución año a año, últimos 12 meses con tasa de ahorro y Previsión a 1–12 meses por categoría." },
          { term: "Flujo del dinero", text: "Diagrama Sankey desde fuentes de ingreso hacia grupos (o categorías) y Ahorrado/Déficit. En el mes en curso muestra debajo un segundo diagrama de Proyección configurable por grupo (Media del mes completo o Prorratear días restantes) donde puedes pulsar cualquier nodo para fijarlo a su importe real." },
        ],
      },
      {
        id: "ask-ai",
        heading: "Pregunta (asistente de filtros con IA)",
        paragraphs: [
          "Con las funciones de IA activadas, aparece la caja Pregunta en Explorador y Transacciones. Escribe una consulta natural (p. ej. «cuánto gasté en restaurantes el verano pasado») y la convierte en los filtros reales de la URL, eligiendo automáticamente entre Explorador y Transacciones cuando hace falta y ofreciendo enlaces directos («Mejor en Explorador →» / «Ver movimientos →»).",
        ],
        tip: "Pregunta nunca envía importes, movimientos ni identificadores de cuenta al proveedor de IA: solo tu texto, la fecha de hoy, el idioma y los nombres del catálogo de categorías.",
      },
    ],
  },
  {
    id: "fire",
    title: "Proyección FIRE y análisis IA del plan",
    summary: "Simulación Monte Carlo hacia la independencia financiera, laboratorio Qué pasaría si, hitos vitales y diagnóstico IA.",
    route: { to: "/fire", labelKey: "nav.fire" },
    sections: [
      {
        id: "fire-projection",
        heading: "Proyección, niveles y escenarios",
        paragraphs: [
          "La página FIRE proyecta tu patrimonio elegible para inversión mediante simulaciones Monte Carlo. Todo lo que introduces está en dinero de hoy y se simula en euros nominales, con un selector en el gráfico entre Dinero futuro y Poder adquisitivo de hoy.",
        ],
        bullets: [
          {
            term: "Año estimado (regla del 50%)",
            text: "El año estimado de cada nivel es el primer año en que al menos la mitad de las simulaciones alcanzan el objetivo. Si menos de la mitad lo logran dentro del horizonte, aparece «Más allá de [horizonte]» junto a la aportación mensual extra estimada para conseguirlo.",
          },
          {
            term: "Niveles y matriz",
            text: "Compara Lean FIRE (solo esenciales), FIRE (gasto actual), Fat FIRE (gasto × multiplicador) y un Objetivo propio opcional frente a tus escenarios de rentabilidad, alternando entre Alcanzar, Supervivencia en retirada y Coast FIRE (si dejas de aportar hoy).",
          },
          {
            term: "Cobertura en el tiempo, atribución y palancas",
            text: "Consulta cómo evolucionó tu % de cobertura trimestre a trimestre, el desglose exacto del último año (lo que aportaste, lo que hizo el mercado y el cambio en lo que necesitas) y las seis palancas ordenadas por impacto.",
          },
        ],
      },
      {
        id: "fire-whatif-settings",
        heading: "Qué pasaría si, hitos vitales y Ajustes",
        paragraphs: [
          "Pulsa Qué pasaría si junto al titular para probar cambios en aportación mensual, gasto, horizonte, tasa de retirada (SWR), aportación única o variación de rentabilidad sin tocar tu plan guardado —o escribe directamente en la barra de IA si está activada.",
        ],
        bullets: [
          {
            term: "Plan y Avanzado",
            text: "Ajusta horizonte, SWR, inflación, fuente de aportación (manual, derivada del ahorro o de movimientos), ventana de gasto (media/mediana, exclusión de meses atípicos, sobreescrituras —con aviso si los últimos 2 meses completos no tienen gastos—), escenarios (incluyendo Sugerir desde la composición de activos según tu última instantánea), clases de activos elegibles, tramos españoles de IRPF del ahorro y flujos futuros.",
          },
          {
            term: "Plantillas de hitos vitales",
            text: "En Avanzado → Eventos de flujo de caja, «Añadir desde plantilla» permite modelar el fin de la hipoteca, el inicio de la pensión pública, reducción de jornada o entradas/salidas puntuales, viendo antes su efecto en el año estimado.",
          },
        ],
      },
      {
        id: "fire-ai-review",
        heading: "Pestaña Análisis IA del plan",
        paragraphs: [
          "Con las funciones de IA activas, la pestaña Análisis IA del plan genera un diagnóstico estructurado y verificado sobre el resumen agregado de tu plan FIRE (nunca sobre transacciones ni IBANs). Puedes comparar progreso respecto a análisis anteriores, hacer preguntas de seguimiento, exportar a Markdown y cargar las propuestas en «Qué pasaría si» con un clic.",
        ],
      },
    ],
  },
  {
    id: "banks",
    title: "Bancos, bandeja de pendientes y reglas",
    summary: "Conexión PSD2 mediante Enable Banking, revisión de movimientos entrantes y categorización automática.",
    route: { to: "/transactions?tab=pending", labelKey: "nav.transactions" },
    sections: [
      {
        id: "bank-linking",
        heading: "Vincular cuentas bancarias",
        paragraphs: [
          "La sincronización bancaria usa Enable Banking (solo lectura). Los propietarios configuran las credenciales de Enable Banking del hogar en Ajustes → Bancos; después, cualquier miembro puede vincular sus propias cuentas pasando la autenticación (SCA) de su banco.",
        ],
        bullets: [
          {
            term: "Lista blanca en Restricted Production",
            text: "Si usas el modo gratuito Restricted Production de Enable Banking, recuerda vincular cada cuenta bancaria en «Activate by linking accounts» dentro del panel de Enable Banking antes de enlazar el banco aquí; de lo contrario la conexión quedará activa pero con 0 cuentas.",
          },
          {
            term: "Horarios de sincronización y pausa",
            text: "Los propietarios eligen 1 o 2 horas diarias en Ajustes → Bancos (respetando el límite de ~4 llamadas automáticas diarias por consentimiento). Quien vinculó la conexión o un propietario puede pulsar Sincronizar ahora o pausar la conexión o cuentas sueltas.",
          },
          {
            term: "Caducidad del consentimiento",
            text: "Los permisos bancarios caducan cada 90–180 días. Siete días antes aparece un aviso superior para volver a vincular sin perder histórico ni reglas.",
          },
        ],
      },
      {
        id: "pending-inbox",
        heading: "Bandeja de pendientes",
        paragraphs: [
          "Los movimientos bancarios llegan a Transacciones → Pendientes para revisarlos antes de entrar en tu contabilidad.",
        ],
        bullets: [
          { term: "Confirmar o confirmar en lote", text: "Edita tipo, categoría o descripción en la propia fila (los cambios quedan locales hasta guardar) y confirma uno a uno o en bloque." },
          { term: "Reemplazar posible duplicado", text: "Si un movimiento coincide con una transacción que ya metiste a mano, Reemplazar actualiza esa transacción con la fecha, importe y descripción del banco sin duplicarla." },
          { term: "Dividir", text: "Parte un único cargo (por ejemplo, compra en supermercado con gastos de hogar) en dos o más transacciones con categorías distintas editando importes o porcentajes." },
          { term: "Combinar", text: "Selecciona dos o más movimientos pendientes (incluso de distintas tarjetas o bancos, o un cargo y su devolución) para fusionarlos en una sola transacción neta, o rechazarlos juntos si suman exactamente cero." },
          { term: "Marcar como movimiento / devolución", text: "Envía traspasos de capital directamente a Patrimonio → Flujos → Movimientos, o registra abonos como gastos negativos de devolución." },
          { term: "Filtros, agrupación y restauración", text: "Filtra los pendientes por conexión, texto, estado de categorización (regla / IA / sin sugerencia) o posibles duplicados, agrupa por conexión o categoría y restaura desde el filtro de rechazados cualquier movimiento descartado por error." },
        ],
      },
      {
        id: "rules",
        heading: "Reglas de categorización e IA",
        paragraphs: [
          "Al confirmar un movimiento se aprende automáticamente una regla contraparte → categoría cuando ninguna regla previa coincidía. Puedes editar, filtrar o borrar reglas en lote en Ajustes → Reglas de categorización (abierto a cualquier miembro).",
        ],
        bullets: [
          {
            term: "Aplicar reglas",
            text: "Vuelve a evaluar tus reglas sobre los movimientos pendientes que aún no tienen sugerencia de regla.",
          },
          {
            term: "Sugerir con IA y Sugerir reglas generalizadas",
            text: "Con la IA activada, Sugerir con IA propone categorías para los pendientes sin sugerencia (ocultando IBANs y sin enviar importes), y Sugerir reglas generalizadas en la página de Reglas agrupa variantes repetidas en reglas «contiene» tras comprobar que no colisionan con tu histórico.",
          },
        ],
      },
    ],
  },
  {
    id: "integrations-import",
    title: "Notificaciones, privacidad IA e importación CSV",
    summary: "Avisos por Telegram, límites de privacidad de la IA, copias CSV y borrado de datos.",
    route: { to: "/settings/import", labelKey: "import.title" },
    sections: [
      {
        id: "telegram",
        heading: "Notificaciones por Telegram",
        paragraphs: [
          "Los propietarios pueden configurar un bot y chat de Telegram en Ajustes → Notificaciones para recibir avisos de transacciones, instantáneas, movimientos, cobros de préstamos, operaciones de cartera, ejecuciones recurrentes, nuevos movimientos bancarios, caducidad de consentimientos y alertas de precio.",
        ],
        tip: "Las importaciones masivas por CSV nunca disparan notificaciones, y el interruptor maestro permite silenciarlo todo sin perder la configuración.",
      },
      {
        id: "ai-privacy",
        heading: "Funciones de IA y privacidad",
        paragraphs: [
          "Todas las funciones de IA son opcionales, están apagadas por defecto y dependen de un único interruptor de propietario en Ajustes → Funciones de IA (solo visible si el servidor tiene clave de IA).",
        ],
        bullets: [
          { term: "Qué nunca sale de tu servidor", text: "Nunca se envían IBANs, identificadores ni tu libro de transacciones completo. La sugerencia bancaria envía solo contraparte, descripción enmascarada, tipo y mes (sin importes); Pregunta y Qué pasaría si solo envían tu texto y nombres de categorías/plantillas; Análisis IA del plan solo envía métricas ya agregadas." },
        ],
      },
      {
        id: "csv-wipe",
        heading: "Importación y exportación CSV y Zona de peligro",
        paragraphs: [
          "En Ajustes → Exportar / Importar datos puedes descargar una copia completa en CSV o importar en lote Transacciones, Plantillas recurrentes, Instantáneas, Movimientos, Cartera, Préstamos, Cobros, Activos, Pasivos y Cuadros de amortización.",
          "Los propietarios disponen también de Eliminar todos los datos en Ajustes → Zona de peligro para vaciar los datos financieros conservando el hogar y sus miembros.",
        ],
      },
    ],
  },
];
