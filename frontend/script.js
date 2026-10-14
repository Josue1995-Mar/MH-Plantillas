
// Si abres este frontend en tu propia máquina (como archivo local file://,
// o servido desde localhost/127.0.0.1), usa tu backend local (mvn spring-boot:run,
// puerto 8080). Si lo abres ya publicado en otro lado, usa el backend de Render.
const API_BASE =
    (window.location.protocol === 'file:' ||
     window.location.hostname === 'localhost' ||
     window.location.hostname === '127.0.0.1')
        ? 'http://localhost:8080'
        : 'https://mh-plantillas-backend.onrender.com';

const NOMBRES_MODO = {
    auto: 'Automático',
    qr: 'Solo actualizar QR',
    completo: 'QR + Emisor y Receptor',
    separado: 'Plantilla emisor y receptor separado',
    mixto: 'Emisor unido + Receptor separado'
};

const SUFIJOS = { auto: '_procesado', qr: '_qr', completo: '_qr_distrito', separado: '_distrito_separado', mixto: '_emisor_unido_receptor_separado' };

function setStatus(html, tipo) {
    const status = document.getElementById('status');
    status.className = tipo || '';
    status.innerHTML = html;
}

function mostrarSeleccion() {
    const files = document.getElementById('file').files;
    const sel = document.getElementById('seleccion');
    const btn = document.getElementById('btn');
    if (files.length > 1) {
        sel.innerHTML = '📦 <b>' + files.length + ' ZIP</b> seleccionados → procesamiento masivo';
        btn.textContent = '🚀 Procesar ' + files.length + ' ZIP';
    } else {
        sel.innerHTML = '';
        btn.textContent = '🚀 Procesar';
    }
}

function descargar(blob, nombre) {
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = nombre;
    a.click();
    setTimeout(() => window.URL.revokeObjectURL(url), 10000);
}

function esc(t) {
    return String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;');
}

function leerHeader(response, nombre) {
    const v = response.headers.get(nombre);
    return v ? decodeURIComponent(v.replace(/\+/g, ' ')) : '';
}

/** Resultado de un procesamiento masivo: totales + tabla por cliente. */
function mostrarMasivo(response, modo) {
    const total = response.headers.get('X-Masivo-Total') || '0';
    const errores = response.headers.get('X-Masivo-Error') || '0';
    const revisar = response.headers.get('X-Masivo-Revisar') || '0';
    let raw = response.headers.get('X-Masivo-Resumen') || '';
    let mas = 0;
    const i = raw.indexOf('&mas=');
    if (i >= 0) { mas = Number(raw.substring(i + 5)); raw = raw.substring(0, i); }
    const filas = raw ? decodeURIComponent(raw.replace(/\+/g, ' ')).split('\n').filter(Boolean) : [];

    let html = '✅ Masivo terminado (' + esc(NOMBRES_MODO[modo]) + '): <b>' + total + '</b> clientes · ' +
        '<span class="' + (Number(errores) > 0 ? 'bad' : '') + '">' + errores + ' con error</span> · ' +
        '<span class="' + (Number(revisar) > 0 ? 'warnc' : '') + '">' + revisar + ' para revisar</span>';
    html += '<div class="tabla-wrap"><table class="tabla"><thead><tr>' +
        '<th>Cliente (ZIP)</th><th>Estado</th><th title="Plantillas">Plant.</th><th>QR</th>' +
        '<th title="Dirección unida ordenada">Dir. unida</th><th title="Fila Distrito dibujada">Distrito</th>' +
        '<th title="Observaciones v1/v2">Obs.</th><th>Revisar</th></tr></thead><tbody>';
    for (const f of filas) {
        const c = f.split('|');
        const ok = c[1] === 'OK';
        const clase = !ok ? 'fila-error' : (Number(c[7]) > 0 ? 'fila-revisar' : '');
        html += '<tr class="' + clase + '"><td>' + esc(c[0]) + '</td><td>' + esc(c[1]) + '</td><td>' + c[2] +
            '</td><td>' + c[3] + '</td><td>' + c[4] + '</td><td>' + c[5] + '</td><td>' + c[6] +
            '</td><td>' + (Number(c[7]) > 0 ? '⚠️ ' + c[7] : '—') + '</td></tr>';
    }
    html += '</tbody></table></div>';
    if (mas > 0) html += '<small>… y ' + mas + ' clientes más.</small><br>';
    html += '<small>📄 El ZIP descargado trae <b>procesados/</b> (el ZIP de cada cliente) y <b>RESUMEN_MASIVO.csv</b> ' +
        '(se abre en Excel) con el detalle completo y lo que hay que revisar.</small>';
    document.querySelector('.container').classList.add('ancho');
    setStatus(html, 'ok');
}

async function upload() {

    const fileInput = document.getElementById('file');
    const btn = document.getElementById('btn');
    const modoInput = document.querySelector('input[name="modo"]:checked');

    if (!modoInput) {
        setStatus('Seleccione qué desea hacer (paso 1)', 'error');
        return;
    }

    if (fileInput.files.length === 0) {
        setStatus('Seleccione un ZIP (paso 2)', 'error');
        return;
    }

    const modo = modoInput.value;
    const masivo = fileInput.files.length > 1;

    const formData = new FormData();
    if (masivo) {
        for (const f of fileInput.files) formData.append('files', f);
    } else {
        formData.append('file', fileInput.files[0]);
    }
    formData.append('modo', modo);

    setStatus(masivo
        ? 'Procesando ' + fileInput.files.length + ' ZIP (' + NOMBRES_MODO[modo] + ')... esto puede tardar unos minutos'
        : 'Procesando (' + NOMBRES_MODO[modo] + ')...');
    btn.disabled = true;

    try {

        const response = await fetch(`${API_BASE}/zip/${masivo ? 'masivo' : 'process'}`, {
            method: 'POST',
            body: formData
        });

        if (!response.ok) {
            const msg = await response.text().catch(() => '');
            throw new Error(msg || 'Error procesando ZIP');
        }

        const blob = await response.blob();

        // Masivo (varios ZIP, o un ZIP que trae el ZIP de cada cliente)
        if (response.headers.get('X-Masivo-Total') !== null) {
            descargar(blob, 'procesado_masivo.zip');
            mostrarMasivo(response, modo);
            return;
        }

        document.querySelector('.container').classList.remove('ancho');
        const nombreOriginal = fileInput.files[0].name.replace(/\.zip$/i, '');
        descargar(blob, nombreOriginal + SUFIJOS[modo] + '.zip');

        // Resumen enviado por el backend
        const total = response.headers.get('X-Plantillas');
        const qr = response.headers.get('X-QR-Actualizados');
        const dist = response.headers.get('X-Distrito-Agregados');
        const dibujados = response.headers.get('X-Distrito-Dibujados');
        const espacio = response.headers.get('X-Espacio-Ajustado');
        const revisarRaw = response.headers.get('X-Revisar');
        const detalleRaw = response.headers.get('X-Detalle');
        const detalle = detalleRaw ? decodeURIComponent(detalleRaw.replace(/\+/g, ' ')).split('\n').filter(Boolean) : [];
        const revisar = revisarRaw ? decodeURIComponent(revisarRaw.replace(/\+/g, ' ')).split('\n').filter(Boolean) : [];

        let resumen = '✅ ZIP procesado: <b>' + NOMBRES_MODO[modo] + '</b>';

        if (total !== null) {
            resumen += '<br>Plantillas encontradas: ' + total;
            resumen += '<br>QR actualizados: ' + qr;
            const obs = response.headers.get('X-Observaciones');
            if (obs !== null) resumen += '<br>Observaciones v1/v2: ' + obs;
            if (modo === 'completo' || modo === 'mixto' || modo === 'auto') {
                resumen += '<br>Distrito agregado: ' + dist;
            }
            if (modo === 'separado' || modo === 'mixto' || modo === 'auto') {
                resumen += '<br>Distrito dibujado: ' + (dibujados || 0);
                resumen += '<br>Espacio para Sucursal: ' + (espacio || 0);
                if (Number(dibujados) === 0 && modo !== 'auto') {
                    resumen += '<br>⚠️ No se encontró una fila de Municipio separada (o ya tenía Distrito)';
                }
            }
            if (detalle.length > 0) {
                resumen += '<div class="detalle"><b>Lo que se detectó y se hizo:</b><br>' +
                    detalle.map(d => '• ' + d.replace(/</g, '&lt;')).join('<br>') + '</div>';
            }
            if (revisar.length > 0) {
                resumen += '<br><span class="warn">⚠️ Revisar en Jaspersoft:<br>' +
                    revisar.map(r => '• ' + r.replace(/</g, '&lt;')).join('<br>') + '</span>';
            }
            if (Number(total) === 0) {
                resumen += '<br>⚠️ El ZIP no contiene archivos .jrxml';
            }
        }

        setStatus(resumen, 'ok');

    } catch (e) {

        setStatus('Error: ' + e.message, 'error');

    } finally {

        btn.disabled = false;
    }
}
