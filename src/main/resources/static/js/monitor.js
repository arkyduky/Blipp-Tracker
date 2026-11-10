/**
 * Safety Net - Parent Live Monitor Controller
 * Polls /ride/{id}/live-status every 2 seconds, renders Leaflet road geometry,
 * plots checkpoint milestones, updates the live rider beacon marker, and updates telemetry
 * matching the approved Stitch design specifications.
 */
(function () {
    let rideId = window.rideId || null;
    const initialRiderName = window.riderName || 'Rider';

    // DOM Elements - Header & Connections
    const safetyConnectionsBtn = document.getElementById('safetyConnectionsBtn');
    const safetyConnectionsPopover = document.getElementById('safetyConnectionsPopover');
    const closeConnectionsPopoverBtn = document.getElementById('closeConnectionsPopoverBtn');
    const riderTelegramInput = document.getElementById('riderTelegramInput');
    const emergencyContactInput = document.getElementById('emergencyContactInput');
    const saveRiderTelegramBtn = document.getElementById('saveRiderTelegramBtn');
    const saveEmergencyContactBtn = document.getElementById('saveEmergencyContactBtn');
    const riderStatusChip = document.getElementById('riderStatusChip');
    const contactStatusChip = document.getElementById('contactStatusChip');

    // DOM Elements - Map & Controls
    const mapElement = document.getElementById('map');
    const recenterBtn = document.getElementById('recenterBtn');
    const recenterTextEl = document.getElementById('recenterText');
    const zoomInBtn = document.getElementById('zoomInBtn');
    const zoomOutBtn = document.getElementById('zoomOutBtn');
    const gpsStatusBadge = document.getElementById('gpsStatusBadge');
    const gpsBadgeText = document.getElementById('gpsBadgeText');

    // DOM Elements - Sidebar Telemetry
    const riderNameEl = document.getElementById('riderName');
    const routeSubtitleEl = document.getElementById('routeSubtitle');
    const riderLivePill = document.getElementById('riderLivePill');
    const riderLiveText = document.getElementById('riderLiveText');
    const updatedCounterEl = document.getElementById('updatedCounter');

    // Answer #1: Current Location
    const currentLocationNameEl = document.getElementById('currentLocationName');
    const currentLocationCoordsEl = document.getElementById('currentLocationCoords');

    // Answer #4: Journey Progress
    const progressPctEl = document.getElementById('progressPct');
    const progressBarFill = document.getElementById('progressBarFill');
    const progressDistanceEl = document.getElementById('progressDistance');
    const progressFinalEtaEl = document.getElementById('progressFinalEta');

    // Answer #3: Next Checkpoint
    const nextCpNameEl = document.getElementById('nextCpName');
    const nextCpMetaEl = document.getElementById('nextCpMeta');

    // Route Milestones
    const milestonesListEl = document.getElementById('milestonesList');

    // Answer #5: Guardian Safety Banner
    const safetyBannerEl = document.getElementById('safetyBanner');
    const safetyIconEl = document.getElementById('safetyIcon');
    const safetyTitleEl = document.getElementById('safetyTitle');
    const safetyDescEl = document.getElementById('safetyDesc');

    // Leaflet state
    let map = null;
    let routeGeoJsonLayer = null;
    let markersLayer = null;
    let riderGpsMarker = null;
    let routeBounds = null;

    let lastLocationUpdatedAt = null;
    let currentRiderName = initialRiderName;

    function formatTime(isoString) {
        if (!isoString) return '--:--';
        try {
            let str = String(isoString).trim();
            if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(str)) {
                str += 'Z';
            }
            const date = new Date(str);
            if (isNaN(date.getTime())) return '--:--';
            return date.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', hour12: true });
        } catch (e) {
            return isoString;
        }
    }

    function initMap() {
        if (!mapElement) return;

        // Custom Leaflet map with disabled standard zoom control (using Stitch custom frosted controls)
        map = L.map('map', {
            zoomControl: false,
            attributionControl: true
        }).setView([10.5276, 76.2144], 10);

        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        }).addTo(map);

        markersLayer = L.layerGroup().addTo(map);

        // Bind custom zoom buttons
        if (zoomInBtn) {
            zoomInBtn.addEventListener('click', () => map.zoomIn());
        }
        if (zoomOutBtn) {
            zoomOutBtn.addEventListener('click', () => map.zoomOut());
        }
        // Recenter button
        if (recenterBtn) {
            recenterBtn.addEventListener('click', () => {
                if (riderGpsMarker) {
                    map.setView(riderGpsMarker.getLatLng(), 13, { animate: true });
                } else if (routeBounds && routeBounds.isValid()) {
                    map.fitBounds(routeBounds, { padding: [50, 50], animate: true });
                }
            });
        }

        // Handle viewport resizes dynamically
        window.addEventListener('resize', () => {
            if (map) map.invalidateSize();
        });
    }

    function initSafetyConnections() {
        if (!safetyConnectionsBtn || !safetyConnectionsPopover) return;

        safetyConnectionsBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            safetyConnectionsPopover.classList.toggle('hidden');
        });

        if (closeConnectionsPopoverBtn) {
            closeConnectionsPopoverBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                safetyConnectionsPopover.classList.add('hidden');
            });
        }

        document.addEventListener('click', (e) => {
            if (!safetyConnectionsPopover.contains(e.target) && e.target !== safetyConnectionsBtn) {
                safetyConnectionsPopover.classList.add('hidden');
            }
        });

        function saveConnections() {
            if (!rideId) {
                console.warn('Cannot save connections: no active ride ID');
                return;
            }
            const riderChatId = riderTelegramInput ? riderTelegramInput.value.trim() : '';
            const contactChatId = emergencyContactInput ? emergencyContactInput.value.trim() : '';

            fetch(`/ride/${rideId}/connections`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    telegramChatId: riderChatId,
                    emergencyContactChatId: contactChatId
                })
            })
            .then(res => res.json())
            .then(data => {
                if (riderStatusChip) {
                    const isConnected = data.telegramChatId && data.telegramChatId.length > 0;
                    riderStatusChip.className = isConnected ? 'connection-chip connected' : 'connection-chip unlinked';
                    riderStatusChip.textContent = isConnected ? '● Connected' : 'Not linked';
                }
                if (contactStatusChip) {
                    const isConnected = data.emergencyContactChatId && data.emergencyContactChatId.length > 0;
                    contactStatusChip.className = isConnected ? 'connection-chip connected' : 'connection-chip unlinked';
                    contactStatusChip.textContent = isConnected ? '● Connected' : 'Not linked';
                }
            })
            .catch(err => console.error('Failed to save connections:', err));
        }

        if (saveRiderTelegramBtn) saveRiderTelegramBtn.addEventListener('click', saveConnections);
        if (saveEmergencyContactBtn) saveEmergencyContactBtn.addEventListener('click', saveConnections);
    }

    function renderRouteGeometry(geoJsonString) {
        if (!geoJsonString || routeGeoJsonLayer != null) return;

        try {
            const geoJsonData = (typeof geoJsonString === 'string')
                ? JSON.parse(geoJsonString)
                : geoJsonString;

            routeGeoJsonLayer = L.geoJSON(geoJsonData, {
                style: {
                    color: '#1e3a8a',
                    weight: 5,
                    opacity: 0.85,
                    lineJoin: 'round',
                    lineCap: 'round'
                }
            }).addTo(map);

            routeBounds = routeGeoJsonLayer.getBounds();
            if (routeBounds && routeBounds.isValid()) {
                map.fitBounds(routeBounds, { padding: [50, 50] });
            }
        } catch (e) {
            console.error('Failed to parse route GeoJSON:', e);
        }
    }

    function computeNearestLocation(data) {
        if (!data.routeGeometry && (!data.checkpoints || data.checkpoints.length === 0)) {
            return 'Awaiting route submission from rider...';
        }

        const currentPos = data.currentPosition || 0.0;
        const originName = data.originName || 'Origin';
        const destName = data.destinationName || 'Destination';

        if (currentPos <= 2.0) {
            return `Departing ${originName}`;
        }
        if (currentPos >= 98.0) {
            return `Arrived at ${destName}`;
        }

        if (data.checkpoints && data.checkpoints.length > 0) {
            let closestCp = data.checkpoints[0];
            let minDiff = Math.abs(closestCp.positionOnRoute - currentPos);

            for (let i = 1; i < data.checkpoints.length; i++) {
                const cp = data.checkpoints[i];
                const diff = Math.abs(cp.positionOnRoute - currentPos);
                if (diff < minDiff) {
                    minDiff = diff;
                    closestCp = cp;
                }
            }

            return `Near ${closestCp.name}`;
        }

        return `En route to ${destName}`;
    }

    function updateMarkers(data) {
        markersLayer.clearLayers();

        const currentPos = data.currentPosition || 0.0;
        const nextCp = (data.checkpoints && Array.isArray(data.checkpoints))
            ? data.checkpoints.find(cp => cp.status !== 'PASSED')
            : null;

        // 1. Origin Marker
        if (data.originLatitude != null && data.originLongitude != null) {
            const originIcon = L.divIcon({
                className: 'custom-pin origin',
                html: '<span>A</span>',
                iconSize: [28, 28],
                iconAnchor: [14, 14]
            });
            const originMarker = L.marker([data.originLatitude, data.originLongitude], { icon: originIcon });
            originMarker.bindPopup(`<b>Origin: ${data.originName || 'Origin'}</b><br>Starting point of route`);
            markersLayer.addLayer(originMarker);
        }

        // 2. Checkpoints
        if (data.checkpoints && Array.isArray(data.checkpoints)) {
            data.checkpoints.forEach((cp, index) => {
                if (cp.latitude == null || cp.longitude == null) return;

                const isDestination = (index === data.checkpoints.length - 1);
                const isPassed = (cp.status === 'PASSED');
                const isNext = (nextCp && nextCp.id === cp.id);

                let pinClass = 'custom-pin';
                let iconContent = cp.sequenceNumber;

                if (isDestination) {
                    pinClass += ' destination';
                    iconContent = '🏁';
                } else if (isPassed) {
                    pinClass += ' checkpoint-passed';
                    iconContent = '✓';
                } else if (isNext) {
                    pinClass += ' checkpoint-next';
                } else {
                    pinClass += ' checkpoint-future';
                }

                const cpIcon = L.divIcon({
                    className: pinClass,
                    html: `<span>${iconContent}</span>`,
                    iconSize: isDestination ? [32, 32] : [26, 26],
                    iconAnchor: isDestination ? [16, 16] : [13, 13]
                });

                const cpMarker = L.marker([cp.latitude, cp.longitude], { icon: cpIcon });
                const distanceKm = cp.distanceFromOriginMeters ? (cp.distanceFromOriginMeters / 1000).toFixed(1) : '--';
                const etaTime = formatTime(cp.expectedTime);

                cpMarker.bindPopup(`
                    <b>Checkpoint ${cp.sequenceNumber}: ${cp.name}</b><br>
                    Status: <span style="font-weight:600">${cp.status}</span><br>
                    Distance from start: ${distanceKm} km<br>
                    Expected Arrival: <b>${etaTime}</b>
                `);

                // Map Label Tooltips matching Stitch parity
                if (isNext) {
                    const remMeters = Math.max(0, (cp.distanceFromOriginMeters || 0) - ((data.totalDistanceMeters || 0) * (currentPos / 100)));
                    const remKm = (remMeters / 1000).toFixed(1);
                    cpMarker.bindTooltip(`Next: ${cp.name} (${remKm} km · ${etaTime})`, {
                        permanent: false,
                        direction: 'top',
                        className: 'stitch-map-label'
                    });
                } else if (isDestination) {
                    cpMarker.bindTooltip(`${cp.name} (Final Destination · ${etaTime})`, {
                        permanent: false,
                        direction: 'top',
                        className: 'stitch-map-label'
                    });
                }

                markersLayer.addLayer(cpMarker);
            });
        }

        // 3. Live Rider Beacon Marker (Stitch Parity)
        if (data.lastKnownLatitude != null && data.lastKnownLongitude != null) {
            const gpsLatLng = [data.lastKnownLatitude, data.lastKnownLongitude];
            const nearestLoc = computeNearestLocation(data);

            const riderHtml = `
                <div class="rider-marker-wrapper">
                    <div class="rider-beacon-tag">
                        <span class="rider-tag-dot"></span>
                        <span class="rider-tag-name">${data.riderName || currentRiderName || 'Rider'}</span>
                        <span class="rider-tag-loc">· ${nearestLoc}</span>
                    </div>
                    <div class="rider-beacon-pin">
                        <div class="rider-radar-halo"></div>
                        <div class="rider-core-pin">
                            <span class="material-symbols-outlined rider-heading-icon">navigation</span>
                        </div>
                    </div>
                </div>
            `;

            const riderIcon = L.divIcon({
                className: 'custom-rider-beacon-icon',
                html: riderHtml,
                iconSize: [160, 70],
                iconAnchor: [80, 56]
            });

            if (!riderGpsMarker) {
                riderGpsMarker = L.marker(gpsLatLng, { icon: riderIcon, zIndexOffset: 1000 }).addTo(map);
            } else {
                riderGpsMarker.setLatLng(gpsLatLng);
                riderGpsMarker.setIcon(riderIcon);
            }

            riderGpsMarker.bindPopup(`
                <b>${data.riderName} (Live GPS)</b><br>
                Location: ${nearestLoc}<br>
                Coordinates: ${data.lastKnownLatitude.toFixed(6)}, ${data.lastKnownLongitude.toFixed(6)}<br>
                State: <b>${data.isMoving ? 'Moving' : 'Stationary'}</b>
            `);

            if (gpsStatusBadge) {
                gpsStatusBadge.className = 'gps-badge active';
                if (gpsBadgeText) gpsBadgeText.textContent = 'Live GPS Active';
            }
        } else {
            if (riderGpsMarker) {
                map.removeLayer(riderGpsMarker);
                riderGpsMarker = null;
            }

            if (gpsStatusBadge) {
                gpsStatusBadge.className = 'gps-badge waiting';
                if (gpsBadgeText) gpsBadgeText.textContent = 'Waiting for rider location...';
            }
        }
    }

    function updateSidebarTelemetry(data) {
        currentRiderName = data.riderName || 'Rider';

        // Recenter button text
        if (recenterTextEl) {
            recenterTextEl.textContent = `Focus on ${currentRiderName}`;
        }

        const hasRoute = !!(data.routeGeometry || (data.checkpoints && data.checkpoints.length > 0));

        // Rider Profile & Route Context
        if (riderNameEl) riderNameEl.textContent = currentRiderName;
        if (routeSubtitleEl) {
            if (hasRoute) {
                routeSubtitleEl.textContent = `${data.originName || 'Origin'} → ${data.destinationName || 'Destination'}`;
            } else {
                routeSubtitleEl.textContent = 'Awaiting route submission from rider...';
            }
        }

        if (riderLivePill && riderLiveText) {
            if (data.isMoving) {
                riderLivePill.className = 'live-pill moving';
                riderLiveText.textContent = `${currentRiderName} is riding`;
            } else {
                riderLivePill.className = 'live-pill stationary';
                riderLiveText.textContent = 'Stationary';
            }
        }

        // Answer #1: Current Location
        const nearestLoc = computeNearestLocation(data);
        if (currentLocationNameEl) {
            currentLocationNameEl.textContent = nearestLoc;
        }
        if (currentLocationCoordsEl) {
            if (data.lastKnownLatitude != null && data.lastKnownLongitude != null) {
                currentLocationCoordsEl.textContent = `${data.lastKnownLatitude.toFixed(4)}° N, ${data.lastKnownLongitude.toFixed(4)}° E`;
            } else {
                currentLocationCoordsEl.textContent = 'GPS coordinates pending';
            }
        }

        // Answer #4: Journey Progress
        const currentPos = data.currentPosition || 0.0;
        const totalDistKm = data.totalDistanceMeters ? (data.totalDistanceMeters / 1000).toFixed(1) : '0.0';
        const coveredKm = data.totalDistanceMeters ? (data.totalDistanceMeters * (currentPos / 100) / 1000).toFixed(1) : '0.0';

        if (progressPctEl) progressPctEl.textContent = `${Math.round(currentPos)}% completed`;
        if (progressBarFill) progressBarFill.style.width = `${Math.min(100, Math.max(0, currentPos))}%`;
        if (progressDistanceEl) progressDistanceEl.textContent = `${coveredKm} / ${totalDistKm} km`;

        // Destination ETA for Progress Card
        if (progressFinalEtaEl) {
            if (data.checkpoints && data.checkpoints.length > 0) {
                const finalCp = data.checkpoints[data.checkpoints.length - 1];
                progressFinalEtaEl.textContent = `Final ETA: ${formatTime(finalCp.expectedTime)}`;
            } else {
                progressFinalEtaEl.textContent = 'Final ETA: --:--';
            }
        }

        // Answer #3: Next Checkpoint Card
        if (hasRoute) {
            const nextCp = (data.checkpoints && Array.isArray(data.checkpoints))
                ? data.checkpoints.find(cp => cp.status !== 'PASSED')
                : null;

            if (nextCp) {
                if (nextCpNameEl) nextCpNameEl.textContent = nextCp.name;
                const remMeters = Math.max(0, (nextCp.distanceFromOriginMeters || 0) - ((data.totalDistanceMeters || 0) * (currentPos / 100)));
                const remKm = (remMeters / 1000).toFixed(1);
                const eta = formatTime(nextCp.expectedTime);
                if (nextCpMetaEl) nextCpMetaEl.textContent = `${remKm} km away · ETA ${eta}`;
            } else {
                if (nextCpNameEl) nextCpNameEl.textContent = data.destinationName || 'Destination';
                if (nextCpMetaEl) nextCpMetaEl.textContent = 'All checkpoints completed · Destination reached';
            }
        } else {
            if (nextCpNameEl) nextCpNameEl.textContent = 'Awaiting route submission from rider...';
            if (nextCpMetaEl) nextCpMetaEl.textContent = 'No checkpoints yet';
        }

        // Route Milestones List (Timeline)
        if (milestonesListEl) {
            milestonesListEl.innerHTML = '';

            if (hasRoute && data.checkpoints && Array.isArray(data.checkpoints)) {
                const nextCp = data.checkpoints.find(cp => cp.status !== 'PASSED');
                data.checkpoints.forEach((cp, index) => {
                    const isPassed = (cp.status === 'PASSED');
                    const isNext = (nextCp && nextCp.id === cp.id);
                    const isFinal = (index === data.checkpoints.length - 1);

                    let iconHtml = '';
                    let labelClass = 'milestone-label';
                    let timeClass = 'milestone-time';
                    let labelText = cp.name;

                    if (isPassed) {
                        iconHtml = '<span class="material-symbols-outlined milestone-dot-passed" style="font-variation-settings: \'FILL\' 1;">check_circle</span>';
                        labelText += ' (Passed)';
                    } else if (isNext) {
                        iconHtml = '<div class="milestone-dot-next"></div>';
                        labelClass += ' next';
                        timeClass += ' highlight';
                    } else if (isFinal) {
                        iconHtml = '<div class="milestone-dot-future"></div>';
                        labelText += ' (Final)';
                    } else {
                        iconHtml = '<div class="milestone-dot-future"></div>';
                    }

                    const eta = formatTime(cp.expectedTime);
                    const timePrefix = (isPassed ? '' : 'ETA ');

                    const row = document.createElement('div');
                    row.className = 'milestone-row';
                    row.innerHTML = `
                        <div class="milestone-icon-slot">${iconHtml}</div>
                        <div class="milestone-body">
                            <span class="${labelClass}">${labelText}</span>
                            <span class="${timeClass}">${timePrefix}${eta}</span>
                        </div>
                    `;
                    milestonesListEl.appendChild(row);
                });
            } else {
                milestonesListEl.innerHTML = `
                    <div class="milestone-empty-state" style="padding: 16px 12px; color: #64748b; font-size: 13px; line-height: 1.5; text-align: center; border: 1px dashed #cbd5e1; border-radius: 8px; margin-top: 8px;">
                        <span class="material-symbols-outlined" style="font-size: 24px; color: #94a3b8; display: block; margin-bottom: 4px;">alt_route</span>
                        No checkpoints generated yet.<br>Rider must share a Google Maps route via Telegram to start monitoring.
                    </div>
                `;
            }
        }

        // Answer #5: Guardian Safety State Banner
        const anyEscalated = data.checkpoints && data.checkpoints.some(cp => cp.status === 'ESCALATED');
        const anyAwaiting = data.checkpoints && data.checkpoints.some(cp => cp.status === 'AWAITING_CONFIRMATION');

        if (safetyBannerEl) {
            if (anyEscalated) {
                safetyBannerEl.className = 'safety-dock-card escalated';
                if (safetyIconEl) safetyIconEl.textContent = 'emergency';
                if (safetyTitleEl) safetyTitleEl.textContent = 'EMERGENCY ALERT ESCALATED';
                if (safetyDescEl) safetyDescEl.textContent = 'Rider has not confirmed safety. Automated escalation triggered.';
            } else if (anyAwaiting) {
                safetyBannerEl.className = 'safety-dock-card stationary-warn';
                if (safetyIconEl) safetyIconEl.textContent = 'warning';
                if (safetyTitleEl) safetyTitleEl.textContent = 'Stationary Tolerance Exceeded';
                if (safetyDescEl) safetyDescEl.textContent = 'Telegram check-in prompt sent to rider. Awaiting response.';
            } else if ((data.stationarySince || data.stationaryDurationSeconds != null) && !data.isMoving) {
                let statSecs;
                if (data.stationaryDurationSeconds != null && data.stationaryDurationSeconds !== undefined) {
                    statSecs = data.stationaryDurationSeconds;
                } else {
                    const now = Date.now();
                    let statStr = String(data.stationarySince).trim();
                    if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(statStr)) {
                        statStr += 'Z';
                    }
                    const statTime = new Date(statStr).getTime();
                    statSecs = Math.max(0, Math.floor((now - statTime) / 1000));
                }
                safetyBannerEl.className = 'safety-dock-card stationary-warn';
                if (safetyIconEl) safetyIconEl.textContent = 'schedule';
                if (safetyTitleEl) safetyTitleEl.textContent = 'Rider Stopped';
                if (safetyDescEl) safetyDescEl.textContent = `Stationary for ${statSecs} seconds. Quiet guardian monitor active.`;
            } else {
                safetyBannerEl.className = 'safety-dock-card fine';
                if (safetyIconEl) safetyIconEl.textContent = 'verified_user';
                if (safetyTitleEl) safetyTitleEl.textContent = 'All Good · No Action Required';
                if (safetyDescEl) safetyDescEl.textContent = 'Quiet guardian monitoring active. We will notify you if anything changes.';
            }
        }
    }

    function updateTimeAgo() {
        if (!updatedCounterEl) return;
        if (!lastLocationUpdatedAt) {
            updatedCounterEl.textContent = 'No GPS yet';
            return;
        }
        const now = Date.now();
        let locStr = String(lastLocationUpdatedAt).trim();
        if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(locStr)) {
            locStr += 'Z';
        }
        const updatedTime = new Date(locStr).getTime();
        const diffSeconds = Math.max(0, Math.floor((now - updatedTime) / 1000));

        if (diffSeconds < 60) {
            updatedCounterEl.textContent = `Updated ${diffSeconds}s ago`;
        } else {
            const diffMins = Math.floor(diffSeconds / 60);
            updatedCounterEl.textContent = `Updated ${diffMins}m ago`;
        }
    }

    function resetToZeroState() {
        rideId = null;
        if (routeGeoJsonLayer) {
            map.removeLayer(routeGeoJsonLayer);
            routeGeoJsonLayer = null;
        }
        if (markersLayer) markersLayer.clearLayers();
        if (riderGpsMarker) {
            map.removeLayer(riderGpsMarker);
            riderGpsMarker = null;
        }
        routeBounds = null;
        lastLocationUpdatedAt = null;

        if (window.history && window.history.replaceState) {
            window.history.replaceState(null, '', '/live');
        }

        currentRiderName = 'Rider';
        if (recenterTextEl) recenterTextEl.textContent = 'Focus on Rider';
        if (riderNameEl) riderNameEl.textContent = 'Rider';
        if (routeSubtitleEl) routeSubtitleEl.textContent = 'Awaiting route submission from rider...';
        if (riderLivePill && riderLiveText) {
            riderLivePill.className = 'live-pill stationary';
            riderLiveText.textContent = 'Waiting for route';
        }
        if (updatedCounterEl) updatedCounterEl.textContent = 'No GPS yet';

        if (currentLocationNameEl) currentLocationNameEl.textContent = 'Awaiting route submission from rider...';
        if (currentLocationCoordsEl) currentLocationCoordsEl.textContent = 'GPS coordinates pending';

        if (progressPctEl) progressPctEl.textContent = '0% completed';
        if (progressBarFill) progressBarFill.style.width = '0%';
        if (progressDistanceEl) progressDistanceEl.textContent = '0.0 / 0.0 km';
        if (progressFinalEtaEl) progressFinalEtaEl.textContent = 'Final ETA: --:--';

        if (nextCpNameEl) nextCpNameEl.textContent = 'Awaiting route submission from rider...';
        if (nextCpMetaEl) nextCpMetaEl.textContent = 'No checkpoints yet';

        if (milestonesListEl) {
            milestonesListEl.innerHTML = `
                <div class="milestone-empty-state" style="padding: 16px 12px; color: #64748b; font-size: 13px; line-height: 1.5; text-align: center; border: 1px dashed #cbd5e1; border-radius: 8px; margin-top: 8px;">
                    <span class="material-symbols-outlined" style="font-size: 24px; color: #94a3b8; display: block; margin-bottom: 4px;">alt_route</span>
                    No checkpoints generated yet.<br>Rider must share a Google Maps route via Telegram to start monitoring.
                </div>
            `;
        }

        if (gpsStatusBadge) {
            gpsStatusBadge.className = 'gps-badge waiting';
            if (gpsBadgeText) gpsBadgeText.textContent = 'Waiting for rider location...';
        }

        if (safetyBannerEl) {
            safetyBannerEl.className = 'safety-dock-card fine';
            if (safetyIconEl) safetyIconEl.textContent = 'verified_user';
            if (safetyTitleEl) safetyTitleEl.textContent = 'All Good · No Action Required';
            if (safetyDescEl) safetyDescEl.textContent = 'Quiet guardian monitoring active. We will notify you if anything changes.';
        }

        if (!discoveryInterval) {
            discoveryInterval = setInterval(checkForActiveRide, 2000);
        }
    }

    function fetchLiveStatus() {
        if (!rideId) {
            return;
        }
        fetch(`/ride/${rideId}/live-status`, {
            headers: { 'Accept': 'application/json' }
        })
        .then(res => {
            if (res.status === 404) {
                // Active ride was cleared/ended on backend
                resetToZeroState();
                return null;
            }
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        })
        .then(data => {
            if (!data) return;
            lastLocationUpdatedAt = data.lastLocationUpdatedAt;
            if (data.routeGeometry) {
                renderRouteGeometry(data.routeGeometry);
            }
            updateMarkers(data);
            updateSidebarTelemetry(data);
            updateTimeAgo();
        })
        .catch(err => {
            console.error('Failed to fetch live status:', err);
        });
    }

    let discoveryInterval = null;

    function checkForActiveRide() {
        if (rideId) return;

        const riderChatId = (riderTelegramInput && riderTelegramInput.value) ? riderTelegramInput.value.trim() : '';
        const url = riderChatId ? `/ride/active?telegramChatId=${encodeURIComponent(riderChatId)}` : '/ride/active';

        fetch(url, {
            headers: { 'Accept': 'application/json' }
        })
        .then(res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        })
        .then(data => {
            if (data && data.active && data.rideId) {
                // Real non-demo ride discovered!
                rideId = data.rideId;
                if (data.riderName) {
                    currentRiderName = data.riderName;
                }
                if (window.history && window.history.replaceState) {
                    window.history.replaceState(null, '', `/ride/${rideId}/live`);
                }
                if (discoveryInterval) {
                    clearInterval(discoveryInterval);
                    discoveryInterval = null;
                }
                // Transition immediately to normal live-monitor polling/rendering
                fetchLiveStatus();
            }
        })
        .catch(err => {
            console.debug('Waiting for active real ride:', err);
        });
    }

    // Initialize
    initMap();
    initSafetyConnections();

    if (!rideId) {
        checkForActiveRide();
        discoveryInterval = setInterval(checkForActiveRide, 2000);
    } else {
        fetchLiveStatus();
    }

    // 2-second live polling interval
    setInterval(fetchLiveStatus, 2000);
    // 1-second time-ago counter
    setInterval(updateTimeAgo, 1000);
})();
