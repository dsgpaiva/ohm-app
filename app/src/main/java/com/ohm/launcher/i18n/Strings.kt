package com.ohm.launcher.i18n

/**
 * UI strings. English text is the key; other languages map from it.
 * A missing translation simply shows the English text.
 */
object Strings {
    fun tr(lang: String, en: String): String = if (lang == "pt") pt[en] ?: en else en

    private val pt: Map<String, String> = mapOf(
        // general
        "Close" to "Fechar", "Cancel" to "Cancelar", "Dismiss" to "Dispensar", "Done" to "Concluído", "Edit" to "Editar",
        "Allow" to "Permitir", "Open" to "Abrir", "Show" to "Mostrar", "Hide" to "Ocultar", "None" to "Nenhuma",
        "On" to "Ligado", "Off" to "Desligado", "Auto" to "Auto", "Automatic" to "Automático", "Manual" to "Manual",
        "Light" to "Claro", "Dark" to "Escuro", "Now" to "Agora", "Source" to "Fonte", "Granted" to "Concedida",
        "Demo" to "Demonstração", "Clear" to "Limpo",
        // status bar / quick settings / weather
        "Quick settings" to "Definições rápidas", "Clean screen" to "Limpar ecrã", "Wi-Fi" to "Wi-Fi", "Bluetooth" to "Bluetooth",
        "PASSENGER AIRBAG" to "AIRBAG PASSAGEIRO", "OFF" to "DESL.", "ON" to "LIG.",
        "Weather unavailable — waiting for connection" to "Meteorologia indisponível — a aguardar ligação",
        "Humidity" to "Humidade", "Wind" to "Vento", "Air quality" to "Qualidade do ar", "Open-Meteo · updated" to "Open-Meteo · atualizado",
        "Mostly clear" to "Pouco nublado", "Partly cloudy" to "Parcialmente nublado", "Overcast" to "Encoberto", "Fog" to "Nevoeiro",
        "Drizzle" to "Chuvisco", "Rain" to "Chuva", "Snow" to "Neve", "Showers" to "Aguaceiros", "Snow showers" to "Aguaceiros de neve",
        "Thunderstorm" to "Trovoada",
        // car panel
        "Locked" to "Trancado", "Unlocked" to "Destrancado", "Trunk" to "Mala", "Close trunk" to "Fechar mala", "Hood" to "Capô",
        "Close hood" to "Fechar capô", "Doors" to "Portas", "Headlights on" to "Faróis ligados", "Passenger airbag off" to "Airbag do passageiro desligado",
        "Rear camera" to "Câmara traseira", "demo, no video feed" to "demonstração, sem imagem", "Check surroundings for safety" to "Verifique a área envolvente",
        "Screen cleaning mode" to "Modo de limpeza do ecrã", "Press and hold anywhere for 3 seconds to exit" to "Mantenha premido 3 segundos para sair",
        // notifications
        "Incoming call" to "Chamada recebida", "Ongoing call" to "Chamada em curso", "Answer" to "Atender", "Decline" to "Recusar",
        "Mute" to "Silenciar", "End" to "Terminar", "New message" to "Nova mensagem", "Reply \"OK\"" to "Responder \"OK\"",
        "On my way, see you in 10 min 👍" to "A caminho, até já daqui a 10 min 👍",
        "No connection" to "Sem ligação", "Maps and weather will use cached data" to "Mapas e meteorologia usam dados guardados",
        // map & navigation
        "Navigate…" to "Navegar…", "Navigate" to "Navegar", "Search address or place" to "Pesquisar morada ou local",
        "Fuel" to "Combustível", "Parking" to "Estacionamento", "Charging" to "Carregamento", "Food" to "Restauração",
        "Home" to "Casa", "Work" to "Trabalho", "Recent" to "Recentes", "Demo drives" to "Viagens de demonstração",
        "Set as Home" to "Definir como Casa", "Set as Work" to "Definir como Trabalho", "Home saved" to "Casa guardada", "Work saved" to "Trabalho guardado",
        "Search unavailable (offline?)" to "Pesquisa indisponível (sem ligação?)", "Nothing found nearby" to "Nada encontrado por perto",
        "Map style" to "Estilo do mapa", "Satellite" to "Satélite", "Heading up" to "Direção em cima", "North up" to "Norte em cima",
        "Zoom in" to "Aproximar", "Zoom out" to "Afastar", "Recenter" to "Centrar",
        "Finding routes…" to "A procurar percursos…", "Couldn't find a route" to "Não foi possível encontrar percurso",
        "Start navigation" to "Iniciar navegação", "Then" to "Depois", "Speed camera" to "Radar", "Speed camera ahead" to "Radar à frente",
        "Open in Google Maps" to "Abrir no Google Maps", "You have arrived" to "Chegou ao destino", "Starting route" to "A iniciar percurso",
        "Rerouting" to "A recalcular", "In" to "Daqui a",
        // dock / drawer
        "Car" to "Carro", "All apps" to "Todas as apps", "Volume down" to "Baixar volume", "Volume up" to "Subir volume",
        "Move left" to "Mover para a esquerda", "Move right" to "Mover para a direita", "Unpin" to "Remover da barra", "Pin to dock" to "Fixar na barra",
        "Apps" to "Apps", "Search apps" to "Pesquisar apps", "Loading apps…" to "A carregar apps…",
        "Tap the pin to add or remove from the dock, the eye to hide" to "Toque no pino para fixar ou remover da barra, no olho para ocultar",
        // media
        "Nothing playing" to "Nada em reprodução", "Allow notification access to control music apps" to "Permita o acesso às notificações para controlar as apps de música",
        "Like" to "Gosto", "Previous" to "Anterior", "Next" to "Seguinte", "Stop" to "Parar", "Play" to "Reproduzir", "Pause" to "Pausa",
        "Back 10 s" to "Recuar 10 s", "Forward 10 s" to "Avançar 10 s", "Search music" to "Pesquisar música", "Queue" to "Fila",
        "Now playing" to "A reproduzir", "Equalizer" to "Equalizador", "No equalizer app found on this unit" to "Nenhum equalizador encontrado nesta unidade",
        "Search in" to "Pesquisar em", "Up next" to "A seguir", "This app doesn't share its queue" to "Esta app não partilha a fila",
        // settings
        "Controls" to "Controlos", "Display" to "Ecrã", "Map" to "Mapa", "Media" to "Multimédia", "CAN live view" to "CAN em direto", "System" to "Sistema",
        "Theme" to "Tema", "Appearance" to "Aspeto", "Auto (headlights)" to "Auto (faróis)", "Auto (sunset)" to "Auto (pôr do sol)",
        "Headlights" to "Faróis", "Sunset" to "Pôr do sol",
        "Also switch Android dark mode" to "Mudar também o modo escuro do Android",
        "So Google Maps, Spotify etc. follow the headlights (may need extra permission on some units)" to "Para o Google Maps, Spotify, etc. seguirem os faróis (pode exigir permissão extra nalgumas unidades)",
        "Layout" to "Disposição", "Driving position" to "Posição de condução", "Left-hand drive" to "Volante à esquerda", "Right-hand drive" to "Volante à direita",
        "Car panel width" to "Largura do painel do carro", "Hide Android status & navigation bars" to "Ocultar barras de estado e navegação do Android",
        "Performance" to "Desempenho", "Low-power mode" to "Modo de baixo consumo",
        "Removes blur, glows and shadows; map limited to 30 fps" to "Remove desfoque, brilhos e sombras; mapa limitado a 30 fps",
        "Car source" to "Origem dos dados", "Data source" to "Fonte de dados", "Head unit (CAN)" to "Auto-rádio (CAN)", "None (GPS only)" to "Nenhuma (só GPS)",
        "Demo simulates gear, speed, doors and lights. Head unit listens for the Junsun/canbox broadcasts set below." to
            "A demonstração simula mudança, velocidade, portas e luzes. Auto-rádio escuta os broadcasts Junsun/canbox definidos abaixo.",
        "Vendor broadcast actions, comma-separated" to "Ações de broadcast do fabricante, separadas por vírgulas", "Save actions" to "Guardar ações",
        "Model" to "Modelo", "Body" to "Carroçaria", "Hatchback" to "Hatchback", "Estate" to "Carrinha", "Coupé" to "Coupé", "Paint" to "Cor",
        "Glacier White" to "Branco Glaciar", "Midnight Black" to "Preto Meia-noite", "Volcano Grey" to "Cinzento Vulcão", "Deep Blue" to "Azul Profundo",
        "Flame Red" to "Vermelho Chama", "Silver" to "Prateado",
        "Units" to "Unidades",
        "Heading up while driving" to "Direção em cima ao conduzir", "3D buildings" to "Edifícios 3D", "Navigation" to "Navegação",
        "Navigation app" to "App de navegação", "Built-in" to "Integrada", "Voice guidance" to "Orientação por voz",
        "Speed camera alerts" to "Alertas de radar", "Avoid tolls" to "Evitar portagens", "Avoid highways" to "Evitar autoestradas",
        "Saved places" to "Locais guardados", "Clear Home & Work" to "Apagar Casa e Trabalho", "Clear recent destinations" to "Apagar destinos recentes",
        "Notification access" to "Acesso às notificações", "Granted — music apps can be controlled" to "Concedido — as apps de música podem ser controladas",
        "Needed to show and control what's playing" to "Necessário para mostrar e controlar o que está a tocar",
        "Demo player when nothing is playing" to "Leitor de demonstração quando nada está a tocar", "Volume step" to "Passo do volume",
        "Dock" to "Barra", "Pin apps from the app drawer" to "Fixe apps a partir da gaveta de apps", "Hidden apps" to "Apps ocultas",
        "None — hide apps from the drawer's edit mode" to "Nenhuma — oculte apps no modo de edição da gaveta",
        "Rear camera app" to "App da câmara traseira", "Opened automatically in reverse (not in demo mode)" to "Aberta automaticamente em marcha-atrás (não em demonstração)",
        "Live values" to "Valores em direto", "No data yet" to "Ainda sem dados", "Demo controls" to "Controlos de demonstração",
        "Front left door" to "Porta dianteira esq.", "Front right door" to "Porta dianteira dir.", "Rear left door" to "Porta traseira esq.", "Rear right door" to "Porta traseira dir.",
        "Doors, trunk and hood only open in P" to "Portas, mala e capô só abrem em P",
        "Language" to "Idioma", "Launcher" to "Launcher", "Set Ohm as the Home app" to "Definir o Ohm como app inicial",
        "Choose Ohm in the Android home-app picker" to "Escolha o Ohm na lista de apps iniciais do Android",
        "Location permission" to "Permissão de localização", "Needed for GPS position and weather" to "Necessária para a posição GPS e meteorologia",
        "Android settings" to "Definições do Android", "Demo events" to "Eventos de demonstração", "Reverse gear" to "Marcha-atrás", "About" to "Sobre",
        "Tesla-style launcher for Android head units. Not affiliated with Tesla, Inc." to "Launcher ao estilo Tesla para auto-rádios Android. Sem afiliação com a Tesla, Inc.",
        "Font: Inter (SIL Open Font License). Weather: Open-Meteo." to "Tipo de letra: Inter (SIL Open Font License). Meteorologia: Open-Meteo.",
        "Maps © OpenStreetMap contributors, © CARTO. Routing: OSRM demo server. Search: Photon (komoot)." to
            "Mapas © contribuidores do OpenStreetMap, © CARTO. Percursos: servidor de demonstração OSRM. Pesquisa: Photon (komoot).",

        // ---- v0.2: onboarding
        "Quick setup" to "Configuração rápida", "Welcome" to "Boas-vindas", "Units" to "Unidades", "Vehicle" to "Veículo", "Permissions" to "Permissões",
        "You can change everything later in Controls." to "Pode alterar tudo mais tarde em Controlos.", "Back" to "Voltar", "Skip setup" to "Saltar configuração",
        "Start driving" to "Começar a conduzir", "Welcome to Ohm" to "Bem-vindo ao Ohm",
        "A calm, minimal home screen for your car. Let's set a few things up." to "Um ecrã inicial calmo e minimalista para o seu carro. Vamos configurar algumas coisas.",
        "Regional preferences — these apply to every vehicle." to "Preferências regionais — aplicam-se a todos os veículos.",
        "Distance" to "Distância", "Speed" to "Velocidade", "Temperature" to "Temperatura", "Fuel consumption" to "Consumo de combustível",
        "Pressure" to "Pressão", "Currency" to "Moeda", "Other (ISO code)" to "Outra (código ISO)", "Your vehicle" to "O seu veículo",
        "Vehicle settings are saved per car, so you can add more cars later." to "As definições do veículo são guardadas por carro, por isso pode adicionar mais carros depois.",
        "Vehicle name" to "Nome do veículo", "Car data" to "Dados do carro",
        "No CAN / MCU service detected on this unit — using demo or GPS only." to "Nenhum serviço CAN / MCU detetado nesta unidade — a usar demonstração ou só GPS.",
        "Automatic follows your headlights (or sunset when the car can't tell)." to "Automático segue os faróis (ou o pôr do sol quando o carro não informa).",
        "Accent colour" to "Cor de destaque",
        "Which app should guide you? Built-in shows directions right on Ohm's map." to "Que app o deve guiar? A integrada mostra as indicações no próprio mapa do Ohm.",
        "Navigation provider" to "Fornecedor de navegação", "Not installed" to "Não instalado", "Other app" to "Outra app",
        "Each one unlocks a feature. All optional." to "Cada uma desbloqueia uma função. Todas opcionais.", "Location" to "Localização",
        "Your position on the map, weather and trips" to "A sua posição no mapa, meteorologia e viagens",
        "Show and control Spotify, YouTube Music, Bluetooth audio…" to "Mostrar e controlar Spotify, YouTube Music, áudio Bluetooth…",
        "Home app" to "App inicial", "Open Ohm when the unit starts and on the Home button" to "Abrir o Ohm ao ligar a unidade e no botão Início",
        // ---- v0.2: settings
        "Trips" to "Viagens", "Diagnostics" to "Diagnóstico", "Vehicles" to "Veículos", "Add vehicle" to "Adicionar veículo", "Delete vehicle" to "Apagar veículo",
        "Everything on this tab belongs to the selected vehicle. Units, theme and language are shared." to "Tudo neste separador pertence ao veículo selecionado. Unidades, tema e idioma são partilhados.",
        "No CAN / MCU service detected on this unit. Enable \"Show unsupported options\" in Diagnostics to configure it anyway." to
            "Nenhum serviço CAN / MCU detetado nesta unidade. Ative \"Mostrar opções não suportadas\" em Diagnóstico para configurar mesmo assim.",
        "Typical consumption (L/100 km)" to "Consumo típico (L/100 km)", "used to estimate trip fuel and cost" to "usado para estimar combustível e custo das viagens",
        "Unknown — trips won't show fuel estimates" to "Desconhecido — as viagens não mostram estimativas de combustível",
        "Reverse camera" to "Câmara de marcha-atrás",
        "No camera app detected — your unit's own reverse camera keeps working as usual." to "Nenhuma app de câmara detetada — a câmara traseira da unidade continua a funcionar normalmente.",
        "Ohm reacts to reverse gear" to "O Ohm reage à marcha-atrás", "Show the camera when reversing" to "Mostrar a câmara ao fazer marcha-atrás",
        "This only changes what Ohm does. The head unit's own reverse-camera switching is never disabled or overridden." to
            "Isto só muda o comportamento do Ohm. A comutação da câmara traseira da própria unidade nunca é desativada nem substituída.",
        "Camera app" to "App da câmara", "None — Ohm shows its own view" to "Nenhuma — o Ohm mostra a sua própria vista", "Trigger" to "Ativação",
        "CAN gear" to "Mudança (CAN)", "MCU broadcast" to "Broadcast do MCU", "Android broadcast" to "Broadcast Android",
        "Broadcast action (see Diagnostics › CAN)" to "Ação de broadcast (ver Diagnóstico › CAN)",
        "Extra meaning \"in reverse\", e.g. state=1 (optional)" to "Extra que indica \"marcha-atrás\", p. ex. state=1 (opcional)",
        "Return to Ohm after reversing" to "Voltar ao Ohm depois da marcha-atrás", "Regional preferences" to "Preferências regionais",
        "Pressure is used for tyre data when a car source provides it." to "A pressão é usada para dados dos pneus quando a fonte do carro os fornece.",
        "Currency & fuel price" to "Moeda e preço do combustível", "Fuel price per litre" to "Preço do combustível por litro", "Not set" to "Não definido",
        "Animations" to "Animações", "Full" to "Completas", "Reduced" to "Reduzidas", "Dashboard" to "Painel", "Car + map" to "Carro + mapa", "Map focus" to "Foco no mapa",
        "Widgets" to "Widgets", "Music widget" to "Widget de música", "Speed-limit sign" to "Sinal de limite de velocidade", "Weather in the top bar" to "Meteorologia na barra superior",
        "Car buttons (lock, trunk, hood)" to "Botões do carro (trancar, mala, capô)", "Contextual dock buttons" to "Botões contextuais na barra",
        "End route, voice, call controls appear in a fixed spot when relevant" to "Terminar percurso, voz e chamadas aparecem num local fixo quando relevante",
        "Navigate with" to "Navegar com", "If the chosen app isn't available, Ohm falls back to its built-in navigation." to "Se a app escolhida não estiver disponível, o Ohm usa a navegação integrada.",
        "Built-in navigation" to "Navegação integrada", "Location provider" to "Fonte de localização", "Use" to "Usar", "Head-unit GPS" to "GPS do auto-rádio",
        "Phone (Ohm Companion)" to "Telemóvel (Ohm Companion)", "External GPS" to "GPS externo",
        "Automatic picks the freshest, most accurate fix. A manual choice falls back to another provider when its data is stale (> 6 s) or inaccurate." to
            "Automático escolhe a posição mais recente e precisa. Uma escolha manual recorre a outra fonte quando os dados estão desatualizados (> 6 s) ou imprecisos.",
        "No location fix" to "Sem posição", "Using" to "A usar", "fallback" to "alternativa", "Ohm Companion (phone)" to "Ohm Companion (telemóvel)",
        "Install the Ohm Companion app on your phone, join the same Wi-Fi or phone hotspot as the head unit, and enter this pairing code:" to
            "Instale a app Ohm Companion no telemóvel, ligue-a à mesma rede Wi-Fi ou hotspot do auto-rádio e introduza este código:",
        "New code" to "Novo código", "Not connected" to "Não ligado",
        "Location, speed, heading, phone battery/network and (optionally) notifications are shared over the local network only." to
            "Localização, velocidade, direção, bateria/rede do telemóvel e (opcionalmente) notificações são partilhadas só na rede local.",
        "External GPS receiver" to "Recetor GPS externo",
        "Any receiver or app that sends NMEA 0183 sentences (RMC/GGA) over UDP to this unit." to "Qualquer recetor ou app que envie frases NMEA 0183 (RMC/GGA) por UDP para esta unidade.",
        "Apply" to "Aplicar", "Media source" to "Fonte multimédia", "Mode" to "Modo", "This device" to "Este dispositivo", "Phone (Bluetooth)" to "Telemóvel (Bluetooth)",
        "This device: apps running on the unit (Spotify, YouTube Music…) — full control and in-app search." to
            "Este dispositivo: apps a correr na unidade (Spotify, YouTube Music…) — controlo total e pesquisa na app.",
        "Phone: a phone connected over Bluetooth (AVRCP) — Ohm shows what's playing and controls it; the phone handles accounts and playback." to
            "Telemóvel: um telemóvel ligado por Bluetooth (AVRCP) — o Ohm mostra e controla o que está a tocar; o telemóvel trata das contas e da reprodução.",
        "Access" to "Acesso", "Spotify library" to "Biblioteca do Spotify",
        "Browsing playlists and your library needs Spotify's Web API (a registered developer client ID and your sign-in). Not in this build — playback control and search through the Spotify app already work." to
            "Navegar nas playlists e biblioteca exige a Web API do Spotify (um client ID de programador registado e o seu início de sessão). Não incluído nesta versão — o controlo e a pesquisa através da app Spotify já funcionam.",
        "Current trip" to "Viagem atual", "No trip in progress — a trip starts automatically when the car moves." to "Nenhuma viagem em curso — começa automaticamente quando o carro se move.",
        "History" to "Histórico", "No trips yet" to "Ainda sem viagens", "Clear history" to "Apagar histórico",
        "Fuel and cost are estimates from the vehicle's typical consumption and your fuel price — this car doesn't report real fuel data yet." to
            "Combustível e custo são estimativas com base no consumo típico e no preço do combustível — este carro ainda não fornece dados reais.",
        "In progress" to "Em curso", "Time" to "Tempo", "Average" to "Média", "Max" to "Máx.", "Fuel (est.)" to "Combustível (est.)",
        "Consumption" to "Consumo", "Cost (est.)" to "Custo (est.)", "Re-scan hardware" to "Detetar hardware de novo", "Share report" to "Partilhar relatório",
        "Show unsupported options" to "Mostrar opções não suportadas", "Offer settings even for hardware Ohm didn't detect" to "Mostrar definições mesmo para hardware que o Ohm não detetou",
        "Capabilities" to "Capacidades", "GPS hardware" to "Hardware GPS", "CAN / MCU service" to "Serviço CAN / MCU", "CAN data received" to "Dados CAN recebidos",
        "Media control (notification access)" to "Controlo multimédia (acesso às notificações)", "Ohm Companion seen" to "Ohm Companion detetado",
        "External GPS seen" to "GPS externo detetado", "Media sessions" to "Sessões multimédia", "Rendering" to "Renderização", "Log" to "Registo",
        "Run quick setup again" to "Repetir configuração rápida",
        // ---- v0.2: dock / panel
        "Hide camera" to "Ocultar câmara", "End call" to "Terminar chamada", "Voice on" to "Voz ligada", "Voice off" to "Voz desligada", "End route" to "Terminar percurso",
        // ---- v0.3: Spotify
        "Your Library" to "A sua biblioteca", "Lyrics" to "Letra", "Devices" to "Dispositivos", "Good morning" to "Bom dia", "Good afternoon" to "Boa tarde",
        "Good evening" to "Boa noite", "Refresh" to "Atualizar", "Liked Songs" to "Músicas de que gosta", "Jump back in" to "Voltar a ouvir",
        "Your top artists" to "Os seus artistas de topo", "Your playlists" to "As suas playlists", "Your podcasts" to "Os seus podcasts", "On repeat" to "Em repetição",
        "Recently played" to "Ouvido recentemente", "Playlist" to "Playlist", "Podcast" to "Podcast", "Artist" to "Artista", "Album" to "Álbum",
        "Content provided by Spotify. Some personalised mixes and editorial playlists aren't available to third-party apps." to
            "Conteúdo fornecido pelo Spotify. Algumas misturas personalizadas e playlists editoriais não estão disponíveis para apps de terceiros.",
        "Songs" to "Músicas", "Artists" to "Artistas", "Albums" to "Álbuns", "Playlists" to "Playlists", "Podcasts" to "Podcasts", "Episodes" to "Episódios",
        "All" to "Tudo", "What do you want to listen to?" to "O que quer ouvir?", "Load more" to "Carregar mais", "No results" to "Sem resultados",
        "Search songs, artists, albums, playlists and podcasts." to "Pesquise músicas, artistas, álbuns, playlists e podcasts.", "Your Episodes" to "Os seus episódios",
        "Nothing here yet" to "Ainda nada aqui", "More" to "Mais", "Add to queue" to "Adicionar à fila", "Save to Liked Songs" to "Guardar em Músicas de que gosta",
        "Remove" to "Remover", "Go to album" to "Ir para o álbum", "Go to artist" to "Ir para o artista", "Open in Spotify" to "Abrir no Spotify",
        "Shuffle" to "Aleatório", "Repeat" to "Repetir", "Follow" to "Seguir", "Following" to "A seguir", "Save" to "Guardar", "Saved" to "Guardado",
        "Spotify only lets third-party apps list the songs of playlists you own or collaborate on. Play and Shuffle still work." to
            "O Spotify só permite que apps de terceiros listem as músicas das playlists que criou ou em que colabora. Reproduzir e Aleatório continuam a funcionar.",
        "Artist top tracks aren't available to third-party apps any more — Play starts the artist's mix in Spotify." to
            "As músicas mais ouvidas dos artistas já não estão disponíveis para apps de terceiros — Reproduzir inicia a mistura do artista no Spotify.",
        "Singles & EPs" to "Singles e EPs", "Now playing" to "A reproduzir", "Next up" to "A seguir", "The queue is empty" to "A fila está vazia",
        "♪ Instrumental" to "♪ Instrumental", "Lyrics from LRCLIB (community-sourced)" to "Letras do LRCLIB (fornecidas pela comunidade)",
        "No lyrics found for this song" to "Sem letra para esta música", "Connect to a device" to "Ligar a um dispositivo",
        "No devices — open Spotify on this unit or your phone. Ohm starts Spotify here automatically when you press Play." to
            "Sem dispositivos — abra o Spotify nesta unidade ou no telemóvel. O Ohm inicia o Spotify aqui automaticamente ao premir Reproduzir.",
        "Spotify Connect: play on this head unit, your phone, or any speaker. Ohm remembers your choice." to
            "Spotify Connect: reproduza neste auto-rádio, no telemóvel ou em qualquer coluna. O Ohm lembra-se da sua escolha.",
        "Playing here" to "A tocar aqui", "Can't be controlled" to "Não pode ser controlado", "Pick something to play" to "Escolha algo para ouvir",
        "Connect Spotify" to "Ligar o Spotify",
        "Ohm talks to Spotify through your own free developer app. One-time setup, about 3 minutes:" to "O Ohm comunica com o Spotify através da sua própria app de programador gratuita. Configuração única, cerca de 3 minutos:",
        "On a phone or computer open developer.spotify.com/dashboard, sign in and create an app. Tick \"Web API\"." to
            "Num telemóvel ou computador abra developer.spotify.com/dashboard, inicie sessão e crie uma app. Marque \"Web API\".",
        "Add this Redirect URI exactly:" to "Adicione exatamente este Redirect URI:",
        "In User Management add the e-mail of your Spotify account (Development Mode: up to 5 users; the app owner needs Premium)." to
            "Em User Management adicione o e-mail da sua conta Spotify (Development Mode: até 5 utilizadores; o dono da app precisa de Premium).",
        "Copy the app's Client ID and paste it here:" to "Copie o Client ID da app e cole-o aqui:", "Waiting for sign-in in the browser…" to "À espera do início de sessão no navegador…",
        "Sign in with Spotify" to "Iniciar sessão com o Spotify",
        "Your password goes only to Spotify's own sign-in page. Ohm stores just an encrypted access token on this unit." to
            "A sua palavra-passe vai apenas para a página de início de sessão do Spotify. O Ohm guarda só um token encriptado nesta unidade.",
        "Signed in as" to "Sessão iniciada como", "Spotify Connect, library, search, queue and lyrics" to "Spotify Connect, biblioteca, pesquisa, fila e letras",
        "Preferred device" to "Dispositivo preferido", "This unit / whatever is active" to "Esta unidade / o que estiver ativo", "Open Spotify inside Ohm" to "Abrir o Spotify dentro do Ohm",
        "The Spotify icon in the dock opens Ohm's Spotify screen instead of the app" to "O ícone do Spotify na barra abre o ecrã Spotify do Ohm em vez da app",
        "Synced lyrics" to "Letras sincronizadas", "From LRCLIB, a free community lyrics database" to "Do LRCLIB, uma base de dados de letras gratuita e comunitária",
        "Sign out of Spotify" to "Terminar sessão do Spotify", "Setup guide" to "Guia de configuração", "Sign-in was cancelled" to "O início de sessão foi cancelado",
        "Sign-in timed out" to "O início de sessão expirou",
        "Connect your Spotify account to browse your library, search, pick a Connect device and see lyrics — like the in-car Spotify app. Needs your own free Spotify developer app (Client ID)." to
            "Ligue a sua conta Spotify para navegar na biblioteca, pesquisar, escolher um dispositivo Connect e ver letras — como a app Spotify do carro. Requer a sua própria app de programador Spotify gratuita (Client ID).",
        // ---- v0.5: phone destination, audio, lockout, updates, OBD
        "Shared location" to "Local partilhado", "From your phone" to "Do seu telemóvel", "Couldn't find" to "Não foi possível encontrar",
        "Audio" to "Áudio", "Speed-sensitive volume" to "Volume sensível à velocidade", "Off" to "Desligado", "Low" to "Baixo", "Medium" to "Médio", "High" to "Alto",
        "Raises the music a little above 40 km/h to cover road noise, and lowers it again as you slow down. Changing the volume yourself sets the new base level." to
            "Sobe um pouco a música acima dos 40 km/h para compensar o ruído da estrada e volta a baixar quando abranda. Se mudar o volume, esse passa a ser o novo nível base.",
        "Equalizer" to "Equalizador", "For the built-in Spotify receiver" to "Para o recetor Spotify integrado", "Bass" to "Graves", "Low-mid" to "Médios-graves",
        "Mid" to "Médios", "High-mid" to "Médios-agudos", "Treble" to "Agudos", "Reset" to "Repor",
        "Other apps (Spotify app, radio, Bluetooth audio) use the head unit's own sound settings." to "Outras apps (app Spotify, rádio, áudio Bluetooth) usam as definições de som do próprio auto-rádio.",
        "Driving lockout" to "Bloqueio em andamento", "Lock distracting features while driving" to "Bloquear funções que distraem durante a condução",
        "Video, games, browsers and typing are disabled while the car moves" to "Vídeo, jogos, navegadores e escrita ficam desativados com o carro em movimento",
        "Active above" to "Ativo acima de", "active now" to "ativo agora", "not active" to "inativo",
        "Saved places, recent destinations, categories, music and calls keep working. Allow individual apps in Controls › Apps." to
            "Locais guardados, destinos recentes, categorias, música e chamadas continuam a funcionar. Permita apps individuais em Controlos › Apps.",
        "Blocked while driving" to "Bloqueada em andamento", "Allowed while driving" to "Permitida em andamento", "No video, game or browser apps found." to "Nenhuma app de vídeo, jogos ou navegador encontrada.",
        "Typing is disabled while driving" to "Escrita desativada durante a condução", "Available when parked" to "Disponível com o carro parado",
        "Updates" to "Atualizações", "Installed version" to "Versão instalada", "Check now" to "Verificar agora", "Ohm is up to date" to "O Ohm está atualizado",
        "Install" to "Instalar", "Downloading" to "A transferir", "Allow Ohm to install updates" to "Permitir que o Ohm instale atualizações",
        "Android asks once: \"Install unknown apps\"" to "O Android pergunta uma vez: \"Instalar apps desconhecidas\"",
        "Update available — Controls › System" to "Atualização disponível — Controlos › Sistema",
        "On your PC run  python tools/serve-updates.py  and paste the address it prints (same Wi-Fi). Any https:// address with an update.json works too." to
            "No PC execute  python tools/serve-updates.py  e cole o endereço que aparece (mesma rede Wi-Fi). Qualquer endereço https:// com um update.json também funciona.",
        "Confirm the installation in the Android dialog. Ohm restarts as the new version." to "Confirme a instalação na janela do Android. O Ohm reinicia na nova versão.",
        "OBD-II adapter" to "Adaptador OBD-II", "Use OBD-II adapter" to "Usar adaptador OBD-II", "Adapter" to "Adaptador", "Choose…" to "Escolher…",
        "Bluetooth permission" to "Permissão Bluetooth", "Allow \"Nearby devices\" so Ohm can talk to the adapter" to "Permita \"Dispositivos próximos\" para o Ohm comunicar com o adaptador",
        "Fuel" to "Combustível", "Petrol" to "Gasolina", "Diesel" to "Gasóleo", "LPG" to "GPL", "Connected" to "Ligado", "Connecting…" to "A ligar…",
        "Talking to the car…" to "A comunicar com o carro…", "Choose the adapter" to "Escolha o adaptador", "Bluetooth permission needed" to "É necessária permissão Bluetooth",
        "Not connected" to "Não ligado", "Read trouble codes" to "Ler códigos de avaria", "Clear codes" to "Apagar códigos", "Confirm: clear codes" to "Confirmar: apagar códigos",
        "No trouble codes stored" to "Sem códigos de avaria guardados", "look up this code online" to "pesquise este código online",
        "This car doesn't report fuel flow — trips keep using the typical-consumption estimate." to "Este carro não indica o caudal de combustível — as viagens continuam a usar a estimativa do consumo típico.",
        "No paired Bluetooth devices — pair the adapter in Android's Bluetooth settings." to "Sem dispositivos Bluetooth emparelhados — emparelhe o adaptador nas definições Bluetooth do Android.",
        "Engine" to "Motor", "Battery" to "Bateria", "Cost" to "Custo",
        "Real data from the car's diagnostic port through an ELM327-compatible Bluetooth adapter: fuel level and consumption, engine temperature, RPM, battery voltage and trouble codes. Pair the adapter in Android's Bluetooth settings first (PIN is usually 1234 or 0000)." to
            "Dados reais da tomada de diagnóstico através de um adaptador Bluetooth compatível com ELM327: nível e consumo de combustível, temperatura do motor, rotações, tensão da bateria e códigos de avaria. Emparelhe primeiro o adaptador nas definições Bluetooth do Android (o PIN costuma ser 1234 ou 0000).",
        "Clearing turns off the check-engine light and resets the emission readiness monitors. The fault comes back if it's still there — fix the cause first." to
            "Apagar desliga a luz de avaria do motor e reinicia os monitores de prontidão de emissões. A avaria volta se continuar presente — resolva primeiro a causa.",
        "No trip in progress" to "Nenhuma viagem em curso", "avg" to "média", "choose a camera app in Controls › Vehicle" to "escolha uma app de câmara em Controlos › Veículo",
    )
}
