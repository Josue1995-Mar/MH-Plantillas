
async function upload() {

    const fileInput = document.getElementById('file');

    const status = document.getElementById('status');

    if (fileInput.files.length === 0) {

        status.innerHTML = 'Seleccione un ZIP';

        return;
    }

    const formData = new FormData();

    formData.append('file', fileInput.files[0]);

    status.innerHTML = 'Procesando...';

    try {

        const response = await fetch(
            'https://mh-plantillas-backend.onrender.com/zip/process',
            {
                method: 'POST',
                body: formData
            }
        );

        if (!response.ok) {

            throw new Error('Error procesando ZIP');
        }

        const blob = await response.blob();

        const url = window.URL.createObjectURL(blob);

        const a = document.createElement('a');

        a.href = url;

        a.download = 'processed.zip';

        a.click();

        status.innerHTML = 'ZIP procesado correctamente';

    } catch (e) {

        status.innerHTML = 'Error: ' + e.message;
    }
}

